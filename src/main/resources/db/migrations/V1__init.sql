-- Chegg POS - schema V1
-- All money values are integer cents. Rates are integer parts-per-million.
-- Timestamps are UTC ISO-8601 text.
-- schema_version itself is created by SchemaManager before this script runs.

-- --------------------------------------------------------------- settings
CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- ---------------------------------------------------------------- identity
CREATE TABLE roles (
    id               INTEGER PRIMARY KEY,
    code             TEXT    NOT NULL UNIQUE,
    name             TEXT    NOT NULL,
    locked           INTEGER NOT NULL DEFAULT 0,
    max_discount_ppm INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE permissions (
    id    INTEGER PRIMARY KEY,
    code  TEXT NOT NULL UNIQUE,
    area  TEXT NOT NULL,
    label TEXT NOT NULL
);

CREATE TABLE role_permissions (
    role_id       INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id                   INTEGER PRIMARY KEY,
    username             TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    password_hash        TEXT,
    full_name            TEXT    NOT NULL,
    role_id              INTEGER NOT NULL REFERENCES roles(id),
    active               INTEGER NOT NULL DEFAULT 1,
    must_change_password INTEGER NOT NULL DEFAULT 0,
    failed_attempts      INTEGER NOT NULL DEFAULT 0,
    locked_until         TEXT,
    last_login_at        TEXT,
    created_at           TEXT    NOT NULL,
    updated_at           TEXT    NOT NULL
);

-- ----------------------------------------------------------------- catalog
CREATE TABLE categories (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE,
    color_hex  TEXT    NOT NULL DEFAULT '#722F37',
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE products (
    id           INTEGER PRIMARY KEY,
    sku          TEXT    NOT NULL UNIQUE,
    name         TEXT    NOT NULL,
    category_id  INTEGER NOT NULL REFERENCES categories(id),
    price_cents  INTEGER NOT NULL CHECK (price_cents >= 0),
    cost_cents   INTEGER NOT NULL DEFAULT 0 CHECK (cost_cents >= 0),
    tax_rate_ppm INTEGER NOT NULL DEFAULT 0,
    active       INTEGER NOT NULL DEFAULT 1,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

CREATE TABLE inventory (
    product_id    INTEGER PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    quantity      INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    reorder_level INTEGER NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    updated_at    TEXT    NOT NULL
);

-- ------------------------------------------------------- shifts and drawer
CREATE TABLE shifts (
    id                  INTEGER PRIMARY KEY,
    terminal_id         TEXT    NOT NULL,
    opened_by           INTEGER NOT NULL REFERENCES users(id),
    opened_at           TEXT    NOT NULL,
    opening_cash_cents  INTEGER NOT NULL CHECK (opening_cash_cents >= 0),
    closed_by           INTEGER REFERENCES users(id),
    closed_at           TEXT,
    counted_cash_cents  INTEGER,
    expected_cash_cents INTEGER,
    variance_cents      INTEGER,
    status              TEXT    NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    notes               TEXT
);

CREATE UNIQUE INDEX ux_shifts_one_open ON shifts(terminal_id) WHERE status = 'OPEN';

CREATE TABLE stock_movements (
    id             INTEGER PRIMARY KEY,
    product_id     INTEGER NOT NULL REFERENCES products(id),
    delta          INTEGER NOT NULL,
    reason         TEXT    NOT NULL,
    reference_type TEXT,
    reference_id   INTEGER,
    user_id        INTEGER REFERENCES users(id),
    shift_id       INTEGER REFERENCES shifts(id),
    created_at     TEXT    NOT NULL
);

-- ------------------------------------------------------------------- sales
CREATE TABLE sales (
    id             INTEGER PRIMARY KEY,
    receipt_number TEXT    NOT NULL UNIQUE,
    shift_id       INTEGER NOT NULL REFERENCES shifts(id),
    cashier_id     INTEGER NOT NULL REFERENCES users(id),
    channel        TEXT    NOT NULL CHECK (channel IN ('COUNTER', 'KIOSK')),
    subtotal_cents INTEGER NOT NULL,
    discount_cents INTEGER NOT NULL DEFAULT 0,
    tax_cents      INTEGER NOT NULL DEFAULT 0,
    rounding_cents INTEGER NOT NULL DEFAULT 0,
    total_cents    INTEGER NOT NULL,
    status         TEXT    NOT NULL CHECK (status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED')),
    kiosk_order_id INTEGER,
    created_at     TEXT    NOT NULL,
    voided_at      TEXT,
    voided_by      INTEGER REFERENCES users(id),
    void_reason    TEXT
);

CREATE TABLE sale_items (
    id               INTEGER PRIMARY KEY,
    sale_id          INTEGER NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    line_no          INTEGER NOT NULL,
    product_id       INTEGER NOT NULL REFERENCES products(id),
    sku_snapshot     TEXT    NOT NULL,
    name_snapshot    TEXT    NOT NULL,
    unit_price_cents INTEGER NOT NULL,
    quantity         INTEGER NOT NULL CHECK (quantity > 0),
    discount_cents   INTEGER NOT NULL DEFAULT 0,
    tax_rate_ppm     INTEGER NOT NULL DEFAULT 0,
    tax_cents        INTEGER NOT NULL DEFAULT 0,
    line_total_cents INTEGER NOT NULL,
    UNIQUE (sale_id, line_no)
);

CREATE TABLE payments (
    id             INTEGER PRIMARY KEY,
    sale_id        INTEGER NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    method         TEXT    NOT NULL CHECK (method IN ('CASH', 'CARD')),
    amount_cents   INTEGER NOT NULL,
    tendered_cents INTEGER NOT NULL,
    change_cents   INTEGER NOT NULL DEFAULT 0,
    reference      TEXT,
    created_at     TEXT    NOT NULL
);

-- ----------------------------------------------------------------- refunds
CREATE TABLE refunds (
    id             INTEGER PRIMARY KEY,
    sale_id        INTEGER NOT NULL REFERENCES sales(id),
    shift_id       INTEGER NOT NULL REFERENCES shifts(id),
    user_id        INTEGER NOT NULL REFERENCES users(id),
    receipt_number TEXT    NOT NULL,
    method         TEXT    NOT NULL CHECK (method IN ('CASH', 'CARD')),
    amount_cents   INTEGER NOT NULL CHECK (amount_cents > 0),
    tax_cents      INTEGER NOT NULL DEFAULT 0,
    reason         TEXT    NOT NULL,
    created_at     TEXT    NOT NULL
);

CREATE TABLE refund_items (
    id           INTEGER PRIMARY KEY,
    refund_id    INTEGER NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,
    sale_item_id INTEGER NOT NULL REFERENCES sale_items(id),
    quantity     INTEGER NOT NULL CHECK (quantity > 0),
    amount_cents INTEGER NOT NULL
);

-- ------------------------------------------------------------------- kiosk
CREATE TABLE kiosk_orders (
    id                INTEGER PRIMARY KEY,
    code              TEXT    NOT NULL UNIQUE,
    status            TEXT    NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    created_at        TEXT    NOT NULL,
    expires_at        TEXT    NOT NULL,
    completed_sale_id INTEGER REFERENCES sales(id),
    subtotal_cents    INTEGER NOT NULL,
    total_cents       INTEGER NOT NULL
);

CREATE TABLE kiosk_order_items (
    id               INTEGER PRIMARY KEY,
    order_id         INTEGER NOT NULL REFERENCES kiosk_orders(id) ON DELETE CASCADE,
    product_id       INTEGER NOT NULL REFERENCES products(id),
    name_snapshot    TEXT    NOT NULL,
    unit_price_cents INTEGER NOT NULL,
    quantity         INTEGER NOT NULL CHECK (quantity > 0)
);

-- --------------------------------------------------------- reports / audit
CREATE TABLE z_reports (
    id                  INTEGER PRIMARY KEY,
    shift_id            INTEGER NOT NULL UNIQUE REFERENCES shifts(id),
    generated_at        TEXT    NOT NULL,
    gross_cents         INTEGER NOT NULL,
    discounts_cents     INTEGER NOT NULL,
    net_cents           INTEGER NOT NULL,
    tax_cents           INTEGER NOT NULL,
    cash_cents          INTEGER NOT NULL,
    card_cents          INTEGER NOT NULL,
    rounding_cents      INTEGER NOT NULL,
    refunds_cents       INTEGER NOT NULL,
    refund_count        INTEGER NOT NULL,
    void_count          INTEGER NOT NULL,
    txn_count           INTEGER NOT NULL,
    opening_cash_cents  INTEGER NOT NULL,
    expected_cash_cents INTEGER NOT NULL,
    counted_cash_cents  INTEGER NOT NULL,
    variance_cents      INTEGER NOT NULL,
    report_text         TEXT    NOT NULL
);

CREATE TABLE audit_log (
    id          INTEGER PRIMARY KEY,
    at          TEXT    NOT NULL,
    user_id     INTEGER REFERENCES users(id),
    shift_id    INTEGER REFERENCES shifts(id),
    action      TEXT    NOT NULL,
    entity_type TEXT,
    entity_id   INTEGER,
    summary     TEXT    NOT NULL,
    details     TEXT
);

CREATE TABLE daily_counters (
    day      TEXT    PRIMARY KEY,
    last_seq INTEGER NOT NULL
);

-- ---------------------------------------------------------------- indexes
CREATE INDEX ix_sales_created   ON sales(created_at);
CREATE INDEX ix_sales_shift     ON sales(shift_id);
CREATE INDEX ix_sales_cashier   ON sales(cashier_id);
CREATE INDEX ix_sale_items_sale ON sale_items(sale_id);
CREATE INDEX ix_payments_sale   ON payments(sale_id);
CREATE INDEX ix_refunds_shift   ON refunds(shift_id);
CREATE INDEX ix_stock_moves_pid ON stock_movements(product_id);
CREATE INDEX ix_audit_at        ON audit_log(at);
CREATE INDEX ix_audit_action    ON audit_log(action);
CREATE INDEX ix_products_name   ON products(name);
