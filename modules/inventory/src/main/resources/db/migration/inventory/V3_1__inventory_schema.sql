-- Inventory context. Migration versions: inventory owns major version 3 (see docs/engineering/conventions.md).
-- Design: docs/design/inventory.md. Why the database enforces the invariants: ADR-0005.
CREATE SCHEMA IF NOT EXISTS inventory;

-- Store ids and SKUs are identifiers, so they compare byte by byte (COLLATE "C"): every "ORDER BY sku FOR UPDATE"
-- locks rows in the same order whatever the server's locale, and no operating-system collation upgrade can reorder
-- (and so corrupt) the indexes built on them.

CREATE TABLE inventory.stock_level (
    store_id   text        COLLATE "C" NOT NULL,
    sku        text        COLLATE "C" NOT NULL,
    on_hand    integer     NOT NULL,
    reserved   integer     NOT NULL,
    version    bigint      NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT stock_level_pk PRIMARY KEY (store_id, sku),
    CONSTRAINT stock_level_on_hand_non_negative CHECK (on_hand >= 0),
    CONSTRAINT stock_level_reserved_non_negative CHECK (reserved >= 0),
    -- The last line of defence against overselling: refused by the database even if application code is wrong.
    CONSTRAINT stock_level_reserved_within_on_hand CHECK (reserved <= on_hand)
);

CREATE TABLE inventory.reservation (
    id             uuid        NOT NULL,
    store_id       text        COLLATE "C" NOT NULL,
    reference      text        NOT NULL,
    status         text        NOT NULL,
    expires_at     timestamptz NOT NULL,
    release_reason text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT reservation_pk PRIMARY KEY (id),
    CONSTRAINT reservation_reference_length CHECK (length(reference) BETWEEN 1 AND 64),
    CONSTRAINT reservation_status_known CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT reservation_release_reason_known CHECK (
        release_reason IN ('ORDER_CANCELLED', 'PAYMENT_FAILED', 'ORDER_ABANDONED', 'EXPIRED', 'OPERATOR')),
    -- A reservation has a release reason exactly when it no longer holds stock.
    CONSTRAINT reservation_reason_iff_ended CHECK ((status IN ('RELEASED', 'EXPIRED')) = (release_reason IS NOT NULL))
);

-- Idempotency by reference, enforced by the database: at most one active (held or confirmed) reservation per
-- reference. Released and expired reservations leave the index, so the reference may reserve again later.
CREATE UNIQUE INDEX reservation_active_reference_uq
    ON inventory.reservation (reference)
    WHERE status IN ('HELD', 'CONFIRMED');

-- The expiry sweeper's scan, in expiry order. Only holds that can still expire are indexed, so it stays small.
CREATE INDEX reservation_held_expires_at_idx
    ON inventory.reservation (expires_at)
    WHERE status = 'HELD';

CREATE TABLE inventory.reservation_line (
    reservation_id uuid    NOT NULL,
    sku            text    COLLATE "C" NOT NULL,
    quantity       integer NOT NULL,
    CONSTRAINT reservation_line_pk PRIMARY KEY (reservation_id, sku),
    CONSTRAINT reservation_line_reservation_fk FOREIGN KEY (reservation_id) REFERENCES inventory.reservation (id),
    CONSTRAINT reservation_line_quantity_positive CHECK (quantity > 0)
);

-- Audit trail of stock changes made by store operations. Append-only (see the trigger below).
CREATE TABLE inventory.stock_adjustment (
    id            uuid        NOT NULL,
    store_id      text        COLLATE "C" NOT NULL,
    sku           text        COLLATE "C" NOT NULL,
    delta         integer     NOT NULL,
    reason        text        NOT NULL,
    on_hand_after integer     NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT stock_adjustment_pk PRIMARY KEY (id),
    CONSTRAINT stock_adjustment_delta_non_zero CHECK (delta <> 0),
    CONSTRAINT stock_adjustment_reason_known CHECK (
        reason IN ('RECEIVED', 'DAMAGED', 'EXPIRED', 'CYCLE_COUNT', 'CORRECTION')),
    CONSTRAINT stock_adjustment_on_hand_after_non_negative CHECK (on_hand_after >= 0)
);

-- "What happened to this SKU at this store?", newest first: the question an operator asks during a stock count.
CREATE INDEX stock_adjustment_store_sku_created_idx
    ON inventory.stock_adjustment (store_id, sku, created_at DESC);

-- An audit trail that can be edited is not an audit trail.
CREATE FUNCTION inventory.reject_audit_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER stock_adjustment_append_only
    BEFORE UPDATE OR DELETE ON inventory.stock_adjustment
    FOR EACH ROW EXECUTE FUNCTION inventory.reject_audit_change();
