-- Catalog context. Migration versions: catalog owns major version 2 (see docs/engineering/conventions.md).
CREATE SCHEMA IF NOT EXISTS catalog;

CREATE TABLE catalog.product (
    sku         text        PRIMARY KEY,
    name_en     text        NOT NULL,
    name_ar     text        NOT NULL,
    category    text        NOT NULL,
    price_minor bigint      NOT NULL,
    currency    char(3)     NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT product_sku_format CHECK (sku ~ '^[A-Z0-9][A-Z0-9-]{2,39}$'),
    CONSTRAINT product_price_positive CHECK (price_minor > 0),
    CONSTRAINT product_names_present CHECK (length(trim(name_en)) > 0 AND length(trim(name_ar)) > 0)
);

-- Browsing one category walks this index in SKU order (keyset pagination, no sort step).
CREATE INDEX product_category_sku_idx ON catalog.product (category, sku);
