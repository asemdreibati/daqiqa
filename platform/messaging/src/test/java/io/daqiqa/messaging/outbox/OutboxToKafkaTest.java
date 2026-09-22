package io.daqiqa.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.contracts.events.StockAdjustedV1;
import io.daqiqa.contracts.events.Topics;
import io.daqiqa.kernel.Ids;
import io.daqiqa.messaging.MessagingTestApplication;
import io.daqiqa.messaging.OutboxWriter;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole path against a real (in-JVM) Kafka broker: events committed through the {@link OutboxWriter} reach Kafka
 * through the relay and the Kafka publisher that {@code daqiqa.messaging.publisher=kafka} selects, keyed by store, with
 * the payload as the value and CloudEvents headers.
 */
@Tag("integration")
@DirtiesContext // stop the broker with this class, while its log directory still exists, not at JVM exit
@EmbeddedKafka(partitions = 3, topics = Topics.INVENTORY)
@SpringBootTest(
        classes = MessagingTestApplication.class,
        properties = {
            "daqiqa.messaging.publisher=kafka",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.producer.acks=all",
            "spring.kafka.producer.properties.enable.idempotence=true",
            "spring.kafka.producer.properties.linger.ms=0"
        })
class OutboxToKafkaTest {

    private static final PostgresTestDatabase DB =
            PostgresTestDatabase.shared().migrate("classpath:db/migration/messaging");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.registerWith(registry);
    }

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxRelay relay;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    EmbeddedKafkaBroker broker;

    @BeforeEach
    void emptyOutbox() {
        DB.truncateSchemas("messaging");
    }

    @Test
    void relaysCommittedEventsToKafkaKeyedByStoreWithTheirPayloadAndCloudEventsHeaders() {
        StockAdjustedV1 first = stockAdjusted("DXB-MARINA", 10);
        StockAdjustedV1 other = stockAdjusted("DXB-JLT", 20);
        StockAdjustedV1 second = stockAdjusted("DXB-MARINA", 11);
        for (StockAdjustedV1 event : List.of(first, other, second)) {
            transaction.executeWithoutResult(status -> writer.append(event));
        }

        RelayRound round = relay.relay();

        // The round returns only once the broker has acknowledged every event, so all of them are readable now.
        assertThat(round).isEqualTo(new RelayRound(true, 3, false));
        List<ConsumerRecord<String, String>> records = consumeAll(3);

        ConsumerRecord<String, String> record = recordFor(records, first);
        assertThat(record.key()).isEqualTo("DXB-MARINA");
        JsonMapper json = JsonMapper.builder().build();
        assertThat(json.readTree(record.value())).isEqualTo(json.readTree(json.writeValueAsString(first)));
        assertThat(headers(record))
                .containsEntry("ce_specversion", "1.0")
                .containsEntry("ce_id", first.eventId().toString())
                .containsEntry("ce_type", StockAdjustedV1.TYPE)
                .containsEntry("ce_source", "daqiqa-server")
                .containsEntry("ce_time", first.occurredAt().toString())
                .containsEntry("content-type", "application/json");

        ConsumerRecord<String, String> later = recordFor(records, second);
        assertThat(later.partition()).as("one store, one partition").isEqualTo(record.partition());
        assertThat(later.offset()).as("in commit order").isGreaterThan(record.offset());
    }

    private List<ConsumerRecord<String, String>> consumeAll(int expected) {
        Map<String, Object> config = new HashMap<>(KafkaTestUtils.consumerProps(broker, "outbox-test", false));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(Topics.INVENTORY));
            return StreamSupport.stream(
                            KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(30), expected)
                                    .spliterator(),
                            false)
                    .toList();
        }
    }

    private static ConsumerRecord<String, String> recordFor(
            List<ConsumerRecord<String, String>> records, StockAdjustedV1 event) {
        return records.stream()
                .filter(record ->
                        headers(record).get("ce_id").equals(event.eventId().toString()))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> headers(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    private static StockAdjustedV1 stockAdjusted(String storeId, int onHandAfter) {
        return new StockAdjustedV1(
                Ids.newTimeOrderedId(),
                Instant.parse("2026-09-22T11:59:00Z"),
                storeId,
                "WATER-1-5L",
                1,
                "RECEIVED",
                onHandAfter,
                0);
    }
}
