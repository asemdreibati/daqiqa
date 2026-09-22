package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Ready-made orders for the domain tests. */
final class Orders {

    static final Instant PLACED_AT = Instant.parse("2026-09-22T12:00:00Z");
    static final ProductPrice MILK = new ProductPrice(
            Sku.of("MILK-FULL-1L"), LocalizedText.of("Full Cream Milk 1L", "حليب كامل الدسم"), aed(695));
    static final ProductPrice BREAD = new ProductPrice(
            Sku.of("BREAD-ARABIC-6PK"), LocalizedText.of("Arabic Bread × 6", "خبز عربي × 6"), aed(300));

    private Orders() {}

    static Order placed(PaymentMethod paymentMethod) {
        PricingResult.Priced basket = (PricingResult.Priced) BasketPricing.price(
                List.of(new SkuQuantity(MILK.sku(), 2), new SkuQuantity(BREAD.sku(), 1)),
                Map.of(MILK.sku(), MILK, BREAD.sku(), BREAD));
        return Order.place(
                OrderId.newId(),
                CustomerId.newId(),
                new PromiseTerms(PromiseId.newId(), StoreId.of("DXB-MARINA"), "EXPRESS_15", 12),
                PLACED_AT,
                GeoPoint.of(25.0772, 55.1390),
                paymentMethod,
                ReservationId.newId(),
                basket);
    }

    static Money aed(long fils) {
        return Money.of(fils, "AED");
    }
}
