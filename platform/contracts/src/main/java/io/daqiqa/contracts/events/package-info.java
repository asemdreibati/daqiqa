/**
 * Integration events: the published language of Daqiqa.
 *
 * <p>Rules for every event in this package:
 *
 * <ul>
 *   <li>It is a record of something that <em>happened</em> (past tense), never a command.
 *   <li>It uses JDK types only (no shared-kernel types), so the contract is stable when internals change.
 *   <li>Its {@link io.daqiqa.contracts.events.IntegrationEvent#type() type} carries a version suffix. Changes are
 *       additive only; a breaking change means a new type ({@code .v2}) published side by side.
 *   <li>Consumers must be idempotent on {@link io.daqiqa.contracts.events.IntegrationEvent#eventId() eventId}:
 *       delivery is at-least-once.
 * </ul>
 *
 * <p>The catalogue with JSON examples lives in {@code docs/architecture/events.md}.
 */
@NullMarked
package io.daqiqa.contracts.events;

import org.jspecify.annotations.NullMarked;
