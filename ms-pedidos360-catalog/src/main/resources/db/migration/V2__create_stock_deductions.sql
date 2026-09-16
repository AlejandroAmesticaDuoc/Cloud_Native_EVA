CREATE TABLE stock_deductions (
    order_id BIGINT PRIMARY KEY,
    released BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_stock_deductions_order CHECK (order_id > 0)
);

CREATE TABLE stock_deduction_items (
    order_id BIGINT NOT NULL REFERENCES stock_deductions(order_id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL,
    PRIMARY KEY (order_id, product_id),
    CONSTRAINT ck_stock_deduction_quantity CHECK (quantity > 0)
);
