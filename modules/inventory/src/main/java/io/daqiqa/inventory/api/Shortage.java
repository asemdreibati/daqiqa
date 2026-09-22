package io.daqiqa.inventory.api;

import io.daqiqa.kernel.Sku;

/** Not enough stock of {@code sku}: {@code requested} were asked for, {@code available} can be sold. */
public record Shortage(Sku sku, int requested, int available) {}
