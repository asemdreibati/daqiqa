package io.daqiqa.promise.domain;

/** A basket no promise can be made for: empty, too many different SKUs, or too many units of one SKU. */
public final class InvalidBasketException extends IllegalArgumentException {

    public InvalidBasketException(String message) {
        super(message);
    }
}
