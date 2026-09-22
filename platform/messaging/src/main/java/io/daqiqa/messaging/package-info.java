/**
 * Messaging: the transactional outbox.
 *
 * <p>Modules never talk to Kafka directly. They {@linkplain io.daqiqa.messaging.OutboxWriter append} integration
 * events inside the database transaction that changes their state; a relay publishes committed events to Kafka
 * afterwards. This removes the dual-write problem (state saved but event lost, or event sent for a rolled-back
 * change). See ADR-0009.
 */
@NullMarked
package io.daqiqa.messaging;

import org.jspecify.annotations.NullMarked;
