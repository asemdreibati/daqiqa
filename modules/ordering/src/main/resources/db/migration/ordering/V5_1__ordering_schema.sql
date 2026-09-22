-- Ordering context. Migration versions: ordering owns major version 5 (see docs/engineering/conventions.md).
CREATE SCHEMA IF NOT EXISTS ordering;

-- "order" is a reserved word, hence customer_order.
CREATE TABLE ordering.customer_order (
    id                 uuid             PRIMARY KEY,
    customer_id        uuid             NOT NULL,
    promise_id         uuid             NOT NULL,
    store_id           text             NOT NULL,
    tier               text             NOT NULL,
    eta_minutes        int              NOT NULL,
    placed_at          timestamptz      NOT NULL,
    deliver_by         timestamptz      NOT NULL, -- placed_at + eta_minutes: the promise to keep, queryable for lateness
    delivery_latitude  double precision NOT NULL,
    delivery_longitude double precision NOT NULL,
    payment_method     text             NOT NULL,
    reservation_id     uuid             NOT NULL, -- inventory's reservation; no foreign key across schemas
    total_minor        bigint           NOT NULL,
    currency           char(3)          NOT NULL,
    status             text             NOT NULL,
    updated_at         timestamptz      NOT NULL,
    CONSTRAINT customer_order_status_known
        CHECK (status IN ('AWAITING_PAYMENT', 'CONFIRMED', 'PAYMENT_FAILED', 'ABANDONED')),
    CONSTRAINT customer_order_payment_method_known CHECK (payment_method IN ('CARD', 'CASH_ON_DELIVERY')),
    -- Cash on delivery has no payment step, so such an order can neither wait for nor fail one.
    CONSTRAINT customer_order_cash_needs_no_payment
        CHECK (payment_method = 'CARD' OR status NOT IN ('AWAITING_PAYMENT', 'PAYMENT_FAILED')),
    CONSTRAINT customer_order_eta_positive CHECK (eta_minutes > 0),
    CONSTRAINT customer_order_deliver_by_after_placement CHECK (deliver_by > placed_at),
    CONSTRAINT customer_order_total_positive CHECK (total_minor > 0),
    CONSTRAINT customer_order_location_valid
        CHECK (delivery_latitude BETWEEN -90 AND 90 AND delivery_longitude BETWEEN -180 AND 180),
    CONSTRAINT customer_order_store_id_format CHECK (store_id ~ '^[A-Z]{3}(-[A-Z0-9]+)+$')
);

-- The abandoned-order sweeper's scan. Partial: only orders still waiting for their payment are in it, so it stays tiny
-- however long the order history grows.
CREATE INDEX customer_order_awaiting_payment_idx
    ON ordering.customer_order (placed_at) WHERE status = 'AWAITING_PAYMENT';

-- "My orders", newest first.
CREATE INDEX customer_order_customer_placed_idx ON ordering.customer_order (customer_id, placed_at DESC);

-- A promise converts to at most one live order, whatever idempotency keys the client uses. An order whose payment
-- failed or that was abandoned no longer uses its promise, so the promise may be tried again while it is valid.
CREATE UNIQUE INDEX customer_order_one_live_order_per_promise
    ON ordering.customer_order (promise_id) WHERE status IN ('AWAITING_PAYMENT', 'CONFIRMED');

-- Lines are immutable snapshots: the product's name and unit price as they were when the order was priced.
CREATE TABLE ordering.order_line (
    order_id         uuid   NOT NULL REFERENCES ordering.customer_order (id),
    line_no          int    NOT NULL,
    sku              text   NOT NULL,
    name_en          text   NOT NULL,
    name_ar          text   NOT NULL,
    quantity         int    NOT NULL,
    unit_price_minor bigint NOT NULL, -- in the order's currency
    CONSTRAINT order_line_pk PRIMARY KEY (order_id, line_no),
    CONSTRAINT order_line_one_line_per_sku UNIQUE (order_id, sku), -- baskets are merged before they are ordered
    CONSTRAINT order_line_line_no_positive CHECK (line_no > 0),
    CONSTRAINT order_line_sku_format CHECK (sku ~ '^[A-Z0-9][A-Z0-9-]{2,39}$'),
    CONSTRAINT order_line_names_present CHECK (length(trim(name_en)) > 0 AND length(trim(name_ar)) > 0),
    CONSTRAINT order_line_quantity_range CHECK (quantity BETWEEN 1 AND 99),
    CONSTRAINT order_line_unit_price_positive CHECK (unit_price_minor > 0)
);

-- Idempotency keys of POST /v1/orders (ADR-0008), scoped to the customer.
CREATE TABLE ordering.idempotency_key (
    customer_id     uuid        NOT NULL,
    key             text        NOT NULL,
    request_hash    text        NOT NULL, -- SHA-256 of the request's canonical form
    order_id        uuid        NOT NULL, -- fixed at the first claim, so that every attempt with the key continues it
    status          text        NOT NULL,
    locked_until    timestamptz,          -- the lease of an in-progress claim
    response_status int,
    response_body   text,                 -- text, not jsonb: replays must return the stored bytes unchanged
    expires_at      timestamptz NOT NULL,
    CONSTRAINT idempotency_key_pk PRIMARY KEY (customer_id, key),
    CONSTRAINT idempotency_key_format CHECK (key ~ '^[A-Za-z0-9_-]{8,64}$'),
    CONSTRAINT idempotency_key_request_hash_format CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_key_status_known CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- In progress: a lease and no response yet. Completed: a response and no lease.
    CONSTRAINT idempotency_key_state_consistent CHECK (
        (status = 'IN_PROGRESS' AND locked_until IS NOT NULL AND response_status IS NULL AND response_body IS NULL)
        OR (status = 'COMPLETED' AND locked_until IS NULL AND response_status BETWEEN 200 AND 599
            AND response_body IS NOT NULL))
);

-- The purge deletes expired keys, oldest first.
CREATE INDEX idempotency_key_expires_at_idx ON ordering.idempotency_key (expires_at);
