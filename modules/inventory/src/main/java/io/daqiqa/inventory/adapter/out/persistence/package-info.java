/**
 * PostgreSQL persistence for the inventory context (schema {@code inventory}), with plain SQL through
 * {@link org.springframework.jdbc.core.simple.JdbcClient}. Collections are bound as arrays ({@code = ANY(:skus)},
 * {@code unnest(:skus, :quantities)}), so each statement has one plan whatever the basket size.
 */
@NullMarked
package io.daqiqa.inventory.adapter.out.persistence;

import org.jspecify.annotations.NullMarked;
