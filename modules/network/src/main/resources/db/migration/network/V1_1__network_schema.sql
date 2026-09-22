-- Network context. Migration versions: network owns major version 1 (see docs/engineering/conventions.md).
CREATE SCHEMA IF NOT EXISTS network;

CREATE TABLE network.dark_store (
    -- "C" collation: ids are ASCII codes, so ORDER BY id is byte order whatever the database locale, the same order
    -- Java's String.compareTo gives (the in-memory store directory sorts that way).
    id               text COLLATE "C" PRIMARY KEY,
    name_en          text             NOT NULL,
    name_ar          text             NOT NULL,
    latitude         double precision NOT NULL,
    longitude        double precision NOT NULL,
    -- A new store takes no orders until store operations opens it.
    status           text             NOT NULL DEFAULT 'CLOSED',
    active_pickers   integer          NOT NULL DEFAULT 0,
    service_radius_m integer          NOT NULL,
    -- Incremented by every change the application makes, so in-memory views apply changes in commit order.
    version          bigint           NOT NULL DEFAULT 0,
    created_at       timestamptz      NOT NULL DEFAULT now(),
    updated_at       timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT dark_store_id_format CHECK (id ~ '^[A-Z]{3}(-[A-Z0-9]+)+$' AND length(id) <= 40),
    CONSTRAINT dark_store_names_present CHECK (length(trim(name_en)) > 0 AND length(trim(name_ar)) > 0),
    CONSTRAINT dark_store_latitude_range CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT dark_store_longitude_range CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT dark_store_status_valid CHECK (status IN ('OPEN', 'BUSY', 'PAUSED', 'CLOSED')),
    CONSTRAINT dark_store_active_pickers_non_negative CHECK (active_pickers >= 0),
    CONSTRAINT dark_store_service_radius_range CHECK (service_radius_m BETWEEN 500 AND 10000),
    CONSTRAINT dark_store_version_non_negative CHECK (version >= 0)
);
