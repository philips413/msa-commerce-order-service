-- 멱등성 키를 회원 단위로 유일하게 바꾼다 (ADR 0005).
-- 전역 유일이면, 다른 회원이 우연히(또는 일부러) 같은 키를 보냈을 때 남의 주문이 "재전송 응답"으로 돌아간다.
ALTER TABLE orders DROP CONSTRAINT orders_idempotency_key_key;
ALTER TABLE orders ADD CONSTRAINT uq_orders_user_idempotency_key UNIQUE (user_id, idempotency_key);
