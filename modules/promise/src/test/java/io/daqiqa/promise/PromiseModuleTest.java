package io.daqiqa.promise;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.daqiqa.inventory.api.AvailabilityQuery;
import io.daqiqa.inventory.api.StockSnapshot;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.CoverageIndex;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreCoverage;
import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.PromiseTokenVerifier;
import io.daqiqa.promise.api.VerifiedPromise;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The whole promise module as the server wires it, with the network and inventory contexts faked at their APIs: a
 * promise made over HTTP is honoured by the {@link PromiseTokenVerifier} that checkout uses. The promise context has no
 * database; JDBC is only on the test classpath through the network module.
 */
@SpringBootTest(
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "daqiqa.promise.token.active-key-id=test",
            "daqiqa.promise.token.keys.test=dGVzdC1vbmx5LXByb21pc2Uta2V5LW9mLTMyLWJ5dGVzIQ=="
        })
@AutoConfigureMockMvc
class PromiseModuleTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");

    @TestConfiguration
    static class OtherContexts {

        @Bean
        CoverageIndex coverageIndex() {
            return point -> List.of(new StoreCoverage(MARINA, ofSeconds(330), 2_000));
        }

        @Bean
        AvailabilityQuery availabilityQuery() {
            return (stores, skus) -> new StockSnapshot(Map.of(MARINA, Map.of(MILK, 5, BREAD, 5)));
        }

        @Bean
        StoreDirectory storeDirectory() {
            DarkStore marina = new DarkStore(
                    MARINA,
                    LocalizedText.of("Dubai Marina", "دبي مارينا"),
                    GeoPoint.of(25.0805, 55.1403),
                    StoreStatus.OPEN,
                    7,
                    3_000);
            return new StoreDirectory() {
                @Override
                public Optional<DarkStore> find(StoreId storeId) {
                    return storeId.equals(MARINA) ? Optional.of(marina) : Optional.empty();
                }

                @Override
                public List<DarkStore> findAll() {
                    return List.of(marina);
                }
            };
        }
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PromiseTokenVerifier verifier;

    @Test
    void aPromiseMadeOverHttpIsHonouredAtCheckout() throws Exception {
        MvcTestResult response = mvc.post()
                .uri("/v1/promises")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"location": {"latitude": 25.0772, "longitude": 55.1390},
                         "items": [{"sku": "MILK-FULL-1L", "quantity": 2}, {"sku": "BREAD-ARABIC-6PK", "quantity": 1}]}""")
                .exchange();

        assertThat(response).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"status": "AVAILABLE",
                         "offer": {"storeId": "DXB-MARINA", "tier": "EXPRESS_15", "etaMinutes": 11,
                                   "expiresAt": "2026-09-22T12:01:30Z"}}""");
        String token = JsonPath.read(response.getResponse().getContentAsString(), "$.offer.token");

        VerifiedPromise verified = verifier.verify(
                token, List.of(new SkuQuantity(BREAD, 1), new SkuQuantity(MILK, 1), new SkuQuantity(MILK, 1)));

        assertThat(verified.storeId()).isEqualTo(MARINA);
        assertThat(verified.tier()).isEqualTo(DeliveryTier.EXPRESS_15);
        assertThat(verified.etaMinutes()).isEqualTo(11);
        assertThat(verified.expiresAt()).isEqualTo(PromiseTestApplication.NOW.plusSeconds(90));
    }
}
