package io.daqiqa.contracts.events;

/** A SKU and a quantity, as carried inside events. */
public record LineItemV1(String sku, int quantity) {}
