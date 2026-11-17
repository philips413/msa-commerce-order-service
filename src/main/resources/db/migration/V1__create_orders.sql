CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    -- 멱등성 키. UNIQUE 제약이 동시 중복 요청의 최종 방어선이다.
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    user_id         BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL,
    total_amount    BIGINT      NOT NULL CHECK (total_amount >= 0),
    created_at      TIMESTAMPTZ NOT NULL
);

-- title, unit_price 는 주문 시점 스냅샷이다 (상품 가격이 바뀌어도 주문은 그대로)
CREATE TABLE order_lines (
    order_id   BIGINT       NOT NULL REFERENCES orders (id),
    line_no    INTEGER      NOT NULL,
    product_id BIGINT       NOT NULL,
    title      VARCHAR(255) NOT NULL,
    unit_price BIGINT       NOT NULL CHECK (unit_price >= 0),
    quantity   INTEGER      NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, line_no)
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
