package io.daqiqa.messaging.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
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
 * ends; the next round starts again at event <i>n</i>. A poison event therefore blocks the relay until someone looks
 * at it, which is why failures and the backlog are metrics worth alerting on.
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
        List<PendingEvent> batch = repository.lockNextBatch(batchSize);
        List<PendingEvent> published = new ArrayList<>(batch.size());
        boolean failed = false;
        for (PendingEvent event : batch) {
            // Any exception from the publisher is a failed publication of this event, whatever its type: record it and
            // stop, rather than rolling back the whole round and re-publishing everything before it.
            try {
                publisher.publish(event);
            } catch (RuntimeException e) {
                repository.recordFailure(event, String.valueOf(e));
                counter("daqiqa.outbox.failures", event).increment();
                log.warn(
                        "Could not publish outbox event {} ({}), attempt {}; the relay stops here and retries it next "
                                + "round",
                        event.id(),
                        event.type(),
                        event.attempts() + 1,
                        e);
                failed = true;
                break;
            }
            published.add(event);
            counter("daqiqa.outbox.published", event).increment();
        }
        repository.markPublished(published, clock.instant());
        return new RelayRound(true, published.size(), failed);
    }

    private Counter counter(String name, PendingEvent event) {
        return Counter.builder(name).tag("type", event.type()).register(meters);
    }
}
