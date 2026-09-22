package io.daqiqa.messaging.publisher;

import io.daqiqa.messaging.outbox.PendingEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

/**
 * CloudEvents attributes as Kafka headers (the protocol binding's binary mode, ADR-0009): the record value is the event
 * itself and the envelope travels in headers, so consumers can route and deduplicate without parsing the payload.
 */
final class CloudEventHeaders {

    static final String SPEC_VERSION = "ce_specversion";
    static final String ID = "ce_id";
    static final String TYPE = "ce_type";
    static final String SOURCE = "ce_source";
    static final String TIME = "ce_time";
    static final String CONTENT_TYPE = "content-type";

    /** Every event is emitted by the one deployable (ADR-0002). */
    static final String SOURCE_VALUE = "daqiqa-server";

    private CloudEventHeaders() {}

    static List<Header> of(PendingEvent event) {
        return List.of(
                header(SPEC_VERSION, "1.0"),
                header(ID, event.id().toString()),
                header(TYPE, event.type()),
                header(SOURCE, SOURCE_VALUE),
                header(TIME, event.occurredAt().toString()), // ISO-8601 in UTC, as RFC 3339 requires
                header(CONTENT_TYPE, "application/json"));
    }

    private static Header header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
