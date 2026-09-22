package io.daqiqa.catalog.application;

import io.daqiqa.kernel.Sku;

/** Raised when a product is requested by SKU and does not exist. */
public final class ProductNotFoundException extends RuntimeException {

    private final Sku sku;

    public ProductNotFoundException(Sku sku) {
        super("No product with SKU " + sku);
        this.sku = sku;
    }

    public Sku sku() {
        return sku;
    }
}
