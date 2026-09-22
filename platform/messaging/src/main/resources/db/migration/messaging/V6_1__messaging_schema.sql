-- Messaging: the transactional outbox (ADR-0009). Migration versions: messaging owns major version 6
-- (see docs/engineering/conventions.md).
CREATE SCHEMA IF NOT EXISTS messaging;

-- One row per integration event, appended in the transaction that made the state change it describes. The relay
-- publishes committed rows to Kafka in seq order and stamps published_at; the purge deletes them after retention.
CREATE TABLE messaging.outbox_event (
    id            uuid        PRIMARY KEY, -- the event id, which consumers deduplicate on; an event is stored once
    seq           bigint      NOT NULL GENERATED ALWAYS AS IDENTITY, -- publication order; unique by construction
    type          text        NOT NULL,
    topic         text        NOT NULL,
    partition_key text        NOT NULL,
    payload       jsonb       NOT NULL,
    occurred_at   timestamptz NOT NULL,
    created_at    timestamptz NOT NULL,
    published_at  timestamptz,
    attempts      int         NOT NULL DEFAULT 0, -- failed publication attempts so far
    last_error    text,
    CONSTRAINT outbox_event_type_present CHECK (length(type) > 0),
    CONSTRAINT outbox_event_topic_present CHECK (length(topic) > 0),
    CONSTRAINT outbox_event_partition_key_present CHECK (length(partition_key) > 0),
    CONSTRAINT outbox_event_payload_is_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT outbox_event_attempts_non_negative CHECK (attempts >= 0)
);

-- The relay's scan: unpublished events in publication order. Published rows leave the index, so it stays tiny however
-- large the table grows, and the relay (and the backlog gauge) read only what is still pending.
CREATE INDEX outbox_event_unpublished_idx ON messaging.outbox_event (seq) WHERE published_at IS NULL;

-- The purge's scan: published events by age.
CREATE INDEX outbox_event_published_at_idx ON messaging.outbox_event (published_at) WHERE published_at IS NOT NULL;
