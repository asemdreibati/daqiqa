package io.daqiqa.inventory.application;

import io.daqiqa.kernel.Sku;

/** The catalog has no product with this SKU, so a store cannot hold stock of it. */
public final class UnknownSkuException extends RuntimeException {

    private final Sku sku;

    public UnknownSkuException(Sku sku) {
        super("SKU " + sku + " is not in the catalog");
        this.sku = sku;
    }

    public Sku sku() {
        return sku;
    }
}
