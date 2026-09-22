package io.daqiqa.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.ordering.domain.PaymentMethod;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlaceOrderCommandTest {

    private static final CustomerId CUSTOMER = CustomerId.of("0192f7c4-7c1e-7cc2-9d3a-2f1a6c0e9b11");
    private static final GeoPoint HOME = GeoPoint.of(25.0772, 55.139);
    private static final List<SkuQuantity> BASKET =
            List.of(SkuQuantity.of("MILK-FULL-1L", 2), SkuQuantity.of("BREAD-ARABIC-6PK", 1));
    private static final PlaceOrderCommand COMMAND = command(BASKET, "token-1", HOME, PaymentMethod.CARD);

    @Test
    void mergesRepeatedSkusKeepingTheOrderInWhichTheyFirstAppear() {
        PlaceOrderCommand command = command(
                List.of(
                        SkuQuantity.of("MILK-FULL-1L", 1),
                        SkuQuantity.of("BREAD-ARABIC-6PK", 1),
                        SkuQuantity.of("MILK-FULL-1L", 1)),
                "token-1",
                HOME,
                PaymentMethod.CARD);

        assertThat(command.lines()).containsExactlyElementsOf(BASKET);
    }

    @Test
    void theSameBasketListedDifferentlyIsTheSameRequest() {
        PlaceOrderCommand reordered = command(BASKET.reversed(), "token-1", HOME, PaymentMethod.CARD);
        PlaceOrderCommand split = command(
                List.of(
                        SkuQuantity.of("BREAD-ARABIC-6PK", 1),
                        SkuQuantity.of("MILK-FULL-1L", 1),
                        SkuQuantity.of("MILK-FULL-1L", 1)),
                "token-1",
                GeoPoint.of(25.07720, 55.1390),
                PaymentMethod.CARD);

        assertThat(reordered.fingerprint()).isEqualTo(COMMAND.fingerprint());
        assertThat(split.fingerprint()).isEqualTo(COMMAND.fingerprint());
    }

    @Test
    void anyMeaningfulDifferenceMakesADifferentRequest() {
        assertThat(List.of(
                        command(BASKET, "token-2", HOME, PaymentMethod.CARD),
                        command(
                                List.of(SkuQuantity.of("MILK-FULL-1L", 3), SkuQuantity.of("BREAD-ARABIC-6PK", 1)),
                                "token-1",
                                HOME,
                                PaymentMethod.CARD),
                        command(BASKET, "token-1", GeoPoint.of(25.0773, 55.139), PaymentMethod.CARD),
                        command(BASKET, "token-1", HOME, PaymentMethod.CASH_ON_DELIVERY),
                        new PlaceOrderCommand(CustomerId.newId(), "token-1", BASKET, HOME, PaymentMethod.CARD)))
                .extracting(PlaceOrderCommand::fingerprint)
                .doesNotContain(COMMAND.fingerprint())
                .doesNotHaveDuplicates();
    }

    @Test
    void refusesABasketOfMoreThanFiftyLinesAndAMissingToken() {
        List<SkuQuantity> tooMany = IntStream.rangeClosed(1, 51)
                .mapToObj(i -> SkuQuantity.of("SKU-" + i, 1))
                .toList();

        assertThatIllegalArgumentException().isThrownBy(() -> command(tooMany, "token-1", HOME, PaymentMethod.CARD));
        assertThatIllegalArgumentException().isThrownBy(() -> command(List.of(), "token-1", HOME, PaymentMethod.CARD));
        assertThatIllegalArgumentException().isThrownBy(() -> command(BASKET, " ", HOME, PaymentMethod.CARD));
    }

    private static PlaceOrderCommand command(
            List<SkuQuantity> lines, String token, GeoPoint location, PaymentMethod method) {
        return new PlaceOrderCommand(CUSTOMER, token, lines, location, method);
    }
}
