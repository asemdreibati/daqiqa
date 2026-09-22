package io.daqiqa.catalog.api;

import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;

/** A sellable product as other contexts see it. */
public record Product(Sku sku, LocalizedText name, String category, Money price) {}
