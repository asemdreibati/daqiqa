package io.daqiqa.inventory.adapter.in.web;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;

/** Parses identifiers from requests; the value objects' own validation decides what is well formed. */
final class Identifiers {

    private Identifiers() {}

    static StoreId storeId(String value) {
        try {
            return StoreId.of(value);
        } catch (IllegalArgumentException malformed) {
            throw new MalformedIdentifierException(malformed);
        }
    }

    static Sku sku(String value) {
        try {
            return Sku.of(value);
        } catch (IllegalArgumentException malformed) {
            throw new MalformedIdentifierException(malformed);
        }
    }
}
