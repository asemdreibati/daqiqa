/**
 * Inventory domain model: stock levels, the rules that decide whether a basket can be held, and the reservation state
 * machine. Plain Java only (enforced by ArchitectureTest), so every rule is unit- and property-tested without a
 * database. The database applies the decisions taken here with guarded statements and re-checks them with constraints.
 */
@NullMarked
package io.daqiqa.inventory.domain;

import org.jspecify.annotations.NullMarked;
