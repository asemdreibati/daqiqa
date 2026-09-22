package io.daqiqa.ordering.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.Claim;
import io.daqiqa.ordering.application.IdempotencyKey;
import io.daqiqa.ordering.application.IdempotencyRecord;
import io.daqiqa.ordering.application.IdempotencyRecord.State;
import io.daqiqa.ordering.application.RequestFingerprint;
import io.daqiqa.ordering.application.StoredResponse;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class JdbcIdempotencyKeyRepositoryTest {

    private static final PostgresTestDatabase DB =
            PostgresTestDatabase.shared().migrate("classpath:db/migration/ordering");
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final Instant LEASE_END = NOW.plusSeconds(60);
    private static final Instant EXPIRY = NOW.plus(Duration.ofHours(24));
    private static final CustomerId CUSTOMER = CustomerId.of("0192f7c4-7c1e-7cc2-9d3a-2f1a6c0e9b11");
    private static final IdempotencyKey KEY = new IdempotencyKey(CUSTOMER, "checkout-0001");
    private static final RequestFingerprint REQUEST = fingerprint(1);
    private static final RequestFingerprint OTHER_REQUEST = fingerprint(2);

    private final JdbcClient jdbc = JdbcClient.create(DB.dataSource());
    private final TransactionTemplate transaction =
            new TransactionTemplate(new DataSourceTransactionManager(DB.dataSource()));
    private final JdbcIdempotencyKeyRepository keys = new JdbcIdempotencyKeyRepository(jdbc);
    private final Claim claim = new Claim(KEY, OrderId.newId());

    @BeforeEach
    void emptySchema() {
        DB.truncateSchemas("ordering");
    }

    @Test
    void claimsAKeyOnlyOnce() {
        assertThat(keys.insertInProgress(claim, REQUEST, LEASE_END, EXPIRY)).isTrue();
        assertThat(keys.insertInProgress(new Claim(KEY, OrderId.newId()), OTHER_REQUEST, LEASE_END, EXPIRY))
                .isFalse();

        assertThat(lock(KEY))
                .contains(new IdempotencyRecord(REQUEST, claim.orderId(), new State.InProgress(LEASE_END), EXPIRY));
    }

    @Test
    void scopesKeysToTheirCustomer() {
        keys.insertInProgress(claim, REQUEST, LEASE_END, EXPIRY);
        IdempotencyKey sameKeyOtherCustomer = new IdempotencyKey(CustomerId.newId(), KEY.value());

        assertThat(keys.insertInProgress(new Claim(sameKeyOtherCustomer, OrderId.newId()), REQUEST, LEASE_END, EXPIRY))
                .isTrue();
    }

    @Test
    void keepsTheResponseByteForByte() {
        // Odd spacing, key order and Arabic text: a jsonb column would normalise all three.
        String body = "{\"status\":\"CONFIRMED\",  \"name\" : {\"ar\": \"حليب كامل الدسم\", \"en\": \"Milk\"}}\n";
        keys.insertInProgress(claim, REQUEST, LEASE_END, EXPIRY);

        transaction.executeWithoutResult(status -> {
            assertThat(keys.lockInProgress(claim)).isTrue();
            keys.complete(claim, new StoredResponse(201, body));
        });

        assertThat(keys.findResponse(claim)).contains(new StoredResponse(201, body));
        assertThat(lock(KEY))
                .map(IdempotencyRecord::state)
                .contains(new State.Completed(new StoredResponse(201, body)));
    }

    @Test
    void completesOnlyAClaimStillInProgressForItsOwnOrder() {
        keys.insertInProgress(claim, REQUEST, LEASE_END, EXPIRY);
        StoredResponse declined = new StoredResponse(402, "{\"code\":\"PAYMENT_DECLINED\"}");

        assertThatIllegalStateException().isThrownBy(() -> keys.complete(new Claim(KEY, OrderId.newId()), declined));
        keys.complete(claim, declined);
        assertThatIllegalStateException().isThrownBy(() -> keys.complete(claim, declined));
    }

    @Test
    void holdsAClaimOnlyWhileItIsInProgressForItsOrder() {
        keys.insertInProgress(claim, REQUEST, LEASE_END, EXPIRY);

        assertThat(holds(claim)).isTrue();
        assertThat(holds(new Claim(KEY, OrderId.newId()))).isFalse();
        keys.complete(claim, new StoredResponse(201, "{}"));
        assertThat(holds(claim)).isFalse();
    }

    @Test
    void movesTheLeaseOnTakeOverAndEndsItOnRelease() {
        keys.insertInProgress(claim, REQUEST, LEASE_END, EXPIRY);

        keys.renewLease(claim, LEASE_END.plusSeconds(60));
        assertThat(lock(KEY)).map(IdempotencyRecord::state).contains(new State.InProgress(LEASE_END.plusSeconds(60)));

        keys.expireLease(claim, NOW);
        assertThat(lock(KEY)).map(IdempotencyRecord::state).contains(new State.InProgress(NOW));
    }

    @Test
    void restartsAnExpiredKeyForAnotherRequestAndOrder() {
        keys.insertInProgress(claim, REQUEST, LEASE_END, EXPIRY);
        keys.complete(claim, new StoredResponse(201, "{}"));
        Claim restarted = new Claim(KEY, OrderId.newId());

        keys.restartInProgress(restarted, OTHER_REQUEST, EXPIRY.plusSeconds(60), EXPIRY.plus(Duration.ofHours(24)));

        assertThat(lock(KEY))
                .contains(new IdempotencyRecord(
                        OTHER_REQUEST,
                        restarted.orderId(),
                        new State.InProgress(EXPIRY.plusSeconds(60)),
                        EXPIRY.plus(Duration.ofHours(24))));
        assertThat(keys.findResponse(claim)).isEmpty();
    }

    @Test
    void deletesExpiredKeysOnlyInBoundedBatches() {
        keys.insertInProgress(
                new Claim(key("expired-0001"), OrderId.newId()), REQUEST, LEASE_END, NOW.minusSeconds(7200));
        keys.insertInProgress(new Claim(key("expired-0002"), OrderId.newId()), REQUEST, LEASE_END, NOW);
        keys.insertInProgress(new Claim(key("current-0003"), OrderId.newId()), REQUEST, LEASE_END, NOW.plusSeconds(1));

        assertThat(keys.deleteExpired(NOW, 1)).isEqualTo(1);
        assertThat(keys.deleteExpired(NOW, 1)).isEqualTo(1);
        assertThat(keys.deleteExpired(NOW, 1)).isZero();
        assertThat(jdbc.sql("SELECT key FROM ordering.idempotency_key")
                        .query(String.class)
                        .list())
                .containsExactly("current-0003");
    }

    @Test
    void theDatabaseRefusesACompletedKeyWithoutAResponse() {
        keys.insertInProgress(claim, REQUEST, LEASE_END, EXPIRY);

        assertThatThrownBy(
                        () -> jdbc.sql("UPDATE ordering.idempotency_key SET status = 'COMPLETED', locked_until = NULL")
                                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("idempotency_key_state_consistent");
    }

    private Optional<IdempotencyRecord> lock(IdempotencyKey key) {
        Optional<IdempotencyRecord> record = transaction.execute(status -> keys.lock(key));
        return Objects.requireNonNull(record);
    }

    private boolean holds(Claim claim) {
        Boolean held = transaction.execute(status -> keys.lockInProgress(claim));
        return Boolean.TRUE.equals(held);
    }

    private static IdempotencyKey key(String value) {
        return new IdempotencyKey(CUSTOMER, value);
    }

    private static RequestFingerprint fingerprint(int seed) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) seed;
        return new RequestFingerprint(HexFormat.of().formatHex(bytes));
    }
}
