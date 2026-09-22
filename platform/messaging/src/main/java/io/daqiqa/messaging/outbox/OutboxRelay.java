package io.daqiqa.messaging.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox events in the order they were appended and marks them published (ADR-0009).
 *
 * <p><b>Why one relay at a time.</b> Kafka keeps the order of records with the same key only if they are sent in that
 * order; two relays publishing different rows concurrently could send a store's second event before its first. Each
 * round therefore runs in one transaction that starts with {@code pg_try_advisory_xact_lock}: the instance that gets
 * the lock is the leader for that round and the others skip it. This is leader election with nothing but the database
 * we already run, and it heals itself: the lock ends with the leader's transaction, even if the leader crashes.
 *
 * <p><b>Why stop at the first failure.</b> If event <i>n</i> cannot be published, a later event with the same key must
 * not overtake it. The round records the failed attempt on event <i>n</i>, marks what it published before it, and
 * ends. Event <i>n</i> is retried after a backoff that doubles with every failure (1 s, 2 s, 4 s, ... up to 5 min by
 * default), and nothing after it is published meanwhile: a broker outage costs little retry traffic, and a poison
 * event blocks the relay until someone fixes it, which is why failures and the backlog are metrics worth alerting on.
 *
 * <p><b>Delivery is at-least-once.</b> If the process dies after the broker acknowledged an event but before the round
 * committed, the event is published again by the next leader. Consumers deduplicate on the event id.
 *
 * <p><b>Ordering guarantee.</b> Events of one aggregate are published in the order they were committed: the
 * transactions that append them are serialised by the aggregate's own row lock, so a later event also gets a higher
 * {@code seq} and becomes visible only after the earlier one committed. Events of unrelated transactions for the same
 * store may interleave; that is fine because they are not causally related.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final EventPublisher publisher;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meters;
    private final int batchSize;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    OutboxRelay(
            OutboxRepository repository,
            EventPublisher publisher,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry meters,
            MessagingProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meters = meters;
        this.batchSize = properties.relay().batchSize();
        this.initialBackoff = properties.relay().initialBackoff();
        this.maxBackoff = properties.relay().maxBackoff();
    }

    /**
     * Runs one round: if this instance can become the leader, publishes up to one batch of events in {@code seq} order.
     * Scheduled every {@code daqiqa.messaging.relay.interval}; tests call it directly.
     */
    @Scheduled(
            fixedDelayString = "${daqiqa.messaging.relay.interval:" + MessagingProperties.Relay.DEFAULT_INTERVAL + "}")
    public RelayRound relay() {
        return Objects.requireNonNull(transaction.execute(status -> relayBatch()));
    }

    private RelayRound relayBatch() {
        if (!repository.tryAcquireRelayLock()) {
            return RelayRound.SKIPPED;
        }
        Instant now = clock.instant();
        List<PendingEvent> batch = repository.lockNextBatch(batchSize);
        List<PendingEvent> published = new ArrayList<>(batch.size());
        boolean failed = false;
        for (PendingEvent event : batch) {
            if (event.nextAttemptAt() != null && now.isBefore(event.nextAttemptAt())) {
                break; // it failed recently and waits out its backoff; nothing after it may overtake it
            }
            // Any exception from the publisher is a failed publication of this event, whatever its type: record it and
            // stop, rather than rolling back the whole round and re-publishing everything before it.
            try {
                publisher.publish(event);
            } catch (RuntimeException e) {
                recordFailure(event, e, now);
                failed = true;
                break;
            }
            published.add(event);
            counter("daqiqa.outbox.published", event).increment();
        }
        repository.markPublished(published, clock.instant());
        return new RelayRound(true, published.size(), failed);
    }

    private void recordFailure(PendingEvent event, RuntimeException failure, Instant now) {
        int attempts = event.attempts() + 1;
        Instant nextAttempt = now.plus(backoff(attempts));
        repository.recordFailure(event, String.valueOf(failure), nextAttempt);
        counter("daqiqa.outbox.failures", event).increment();
        // A poison event fails on every attempt until someone fixes it. Its 1st, 2nd, 4th, 8th... failures are logged
        // at WARN, which with the doubling backoff is a handful of lines an hour; the others at DEBUG.
        if (Integer.bitCount(attempts) == 1) {
            log.warn(
                    "Could not publish outbox event {} ({}), attempt {}; nothing after it is published until it is. "
                            + "Next attempt at {}",
                    event.id(),
                    event.type(),
                    attempts,
                    nextAttempt,
                    failure);
        } else {
            log.debug("Could not publish outbox event {}, attempt {}: {}", event.id(), attempts, failure.toString());
        }
    }

    /** {@code initialBackoff} doubled for every failure after the first, capped at {@code maxBackoff}. */
    Duration backoff(int attempts) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempts - 1, 30));
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    private Counter counter(String name, PendingEvent event) {
        return Counter.builder(name).tag("type", event.type()).register(meters);
    }
}
