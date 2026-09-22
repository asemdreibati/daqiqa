/**
 * The transactional outbox: the table, the writer modules append to, the relay that publishes committed events in
 * order under a PostgreSQL advisory lock, the purge that keeps the table small, and the {@link
 * io.daqiqa.messaging.outbox.EventPublisher} port the relay publishes through. See ADR-0009 and
 * {@code docs/design/ordering.md}.
 */
@NullMarked
package io.daqiqa.messaging.outbox;

import org.jspecify.annotations.NullMarked;
