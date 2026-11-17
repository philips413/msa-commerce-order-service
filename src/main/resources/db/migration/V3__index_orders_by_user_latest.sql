-- 내 주문 목록(최신순, 커서 페이징): WHERE user_id = ? AND id < ? ORDER BY id DESC LIMIT ?
-- 이 복합 인덱스 하나로 조건·정렬·LIMIT 을 모두 처리한다. 기존 user_id 단일 인덱스는 이것으로 대체된다.
CREATE INDEX idx_orders_user_id_id ON orders (user_id, id DESC);
DROP INDEX idx_orders_user_id;
