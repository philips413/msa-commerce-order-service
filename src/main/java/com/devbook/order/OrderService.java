package com.devbook.order;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

@Service
class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    record OrderItemCommand(long productId, int quantity) {}

    sealed interface PlaceResult {
        record Created(Order order) implements PlaceResult {}
        /** 같은 멱등성 키로 이미 처리된 주문 */
        record Replayed(Order order) implements PlaceResult {}
        record ProductNotFound(Set<Long> missingProductIds) implements PlaceResult {}
        record ProductServiceUnavailable() implements PlaceResult {}
    }

    /** 커서 기반 페이지. nextCursor 가 비어 있으면 마지막 페이지다. */
    record OrderPage(List<Order> orders, Optional<Long> nextCursor) {}

    private final OrderRepository orders;
    private final ProductClient productClient;
    private final Clock clock;

    OrderService(OrderRepository orders, ProductClient productClient, Clock clock) {
        this.orders = orders;
        this.productClient = productClient;
        this.clock = clock;
    }

    /**
     * 의도적으로 @Transactional 을 붙이지 않는다 — 상품 서비스 HTTP 호출 동안 DB 커넥션을 잡지 않기 위해서다.
     * 저장은 saveAndFlush 한 번(자체 트랜잭션)으로 끝난다.
     */
    // createdAt 은 DB(timestamptz) 정밀도인 마이크로초로 자른다 — 201 응답과 재전송(200) 응답 본문이 같아야 하기 때문
    PlaceResult place(String idempotencyKey, long userId, List<OrderItemCommand> items) {
        Optional<Order> existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            log.info("idempotent replay key={} orderId={}", idempotencyKey, existing.get().getId());
            return new PlaceResult.Replayed(existing.get());
        }

        Set<Long> productIds = new LinkedHashSet<>();
        items.forEach(i -> productIds.add(i.productId()));

        return switch (productClient.fetch(productIds)) {
            case ProductClient.FetchResult.Unavailable u -> new PlaceResult.ProductServiceUnavailable();
            case ProductClient.FetchResult.Found found -> {
                Set<Long> missing = new LinkedHashSet<>(productIds);
                missing.removeAll(found.byId().keySet());
                if (!missing.isEmpty()) {
                    yield new PlaceResult.ProductNotFound(Set.copyOf(missing));
                }
                List<OrderLine> lines = new ArrayList<>();
                for (OrderItemCommand item : items) {
                    ProductClient.ProductSnapshot p = found.byId().get(item.productId());
                    lines.add(new OrderLine(p.id(), p.title(), p.price(), item.quantity()));
                }
                yield save(Order.place(idempotencyKey, userId, lines, Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));
            }
        };
    }

    private PlaceResult save(Order order) {
        try {
            return new PlaceResult.Created(orders.saveAndFlush(order));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 요청이 동시에 들어와 위의 조회를 둘 다 통과한 경우. UNIQUE 제약이 막았으니 먼저 저장된 주문을 돌려준다.
            // 키 충돌이 아닌 다른 제약 위반이면 조회 결과가 없으므로 그대로 다시 던진다.
            return orders.findByUserIdAndIdempotencyKey(order.getUserId(), order.getIdempotencyKey())
                    .<PlaceResult>map(PlaceResult.Replayed::new)
                    .orElseThrow(() -> e);
        }
    }

    Optional<Order> find(long id) {
        return orders.findWithLinesById(id);
    }

    /**
     * 회원의 주문을 최신순으로 size 개. cursor 는 이전 페이지의 마지막 주문 id(이것보다 작은 id 부터).
     * 다음 페이지 여부는 size+1 개를 조회해 판단한다(COUNT 쿼리 없이).
     */
    OrderPage listMine(long userId, Optional<Long> cursor, int size) {
        List<Long> ids = orders.findByUserIdAndIdLessThanOrderByIdDesc(
                        userId, cursor.orElse(Long.MAX_VALUE), Limit.of(size + 1))
                .stream()
                .map(OrderRepository.OrderIdView::getId)
                .toList();
        if (ids.isEmpty()) {
            return new OrderPage(List.of(), Optional.empty());
        }
        boolean hasNext = ids.size() > size;
        List<Long> pageIds = hasNext ? ids.subList(0, size) : ids;
        Optional<Long> nextCursor = hasNext ? Optional.of(pageIds.get(pageIds.size() - 1)) : Optional.empty();
        return new OrderPage(orders.findByIdInOrderByIdDesc(pageIds), nextCursor);
    }
}
