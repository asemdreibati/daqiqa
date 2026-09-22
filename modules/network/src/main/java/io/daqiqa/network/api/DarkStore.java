package io.daqiqa.network.api;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;

/** A micro-fulfilment centre ("dark store") embedded in a neighbourhood. */
public record DarkStore(
        StoreId id,
        LocalizedText name,
        GeoPoint location,
        StoreStatus status,
        int activePickers,
        int serviceRadiusMeters) {}
