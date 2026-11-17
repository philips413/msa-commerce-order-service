package com.devbook.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    /** Gateway 가 검증된 JWT 의 sub 를 넣어 주는 헤더. 외부에서 온 값은 엣지가 지운다 (ADR 0005). */
    static final String USER_ID_HEADER = "X-User-Id";
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    /** 주문자는 토큰의 회원이다. 본문으로 회원을 받지 않는다 (남의 이름으로 주문 방지). */
    record PlaceOrderRequest(
            @NotEmpty @Size(max = 50) List<@Valid @NotNull Item> items) {

        record Item(@NotNull @Positive Long productId, @Min(1) @Max(100) int quantity) {}
    }

    record OrderResponse(long id, long userId, OrderStatus status, long totalAmount,
                         List<LineResponse> items, Instant createdAt) {

        record LineResponse(long productId, String title, long unitPrice, int quantity) {}

        static OrderResponse from(Order o) {
            List<LineResponse> lines = o.getLines().stream()
                    .map(l -> new LineResponse(l.getProductId(), l.getTitle(), l.getUnitPrice(), l.getQuantity()))
                    .toList();
            return new OrderResponse(o.getId(), o.getUserId(), o.getStatus(), o.getTotalAmount(), lines, o.getCreatedAt());
        }
    }

    /** nextCursor: 다음 페이지 요청에 그대로 넘길 값. 마지막 페이지면 null (계약 문서). */
    record OrderPageResponse(List<OrderResponse> items, String nextCursor) {}

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    ResponseEntity<?> place(HttpServletRequest httpRequest,
                            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                            @Valid @RequestBody PlaceOrderRequest request) {
        Long userId = authenticatedUserId(httpRequest);
        if (userId == null) {
            return unauthenticated();
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            return ResponseEntity.badRequest().body(new ErrorResponse("IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key 헤더가 필요합니다(1~64자)."));
        }
        List<OrderService.OrderItemCommand> items = request.items().stream()
                .map(i -> new OrderService.OrderItemCommand(i.productId(), i.quantity()))
                .toList();

        return switch (orderService.place(idempotencyKey, userId, items)) {
            case OrderService.PlaceResult.Created c ->
                    ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(c.order()));
            case OrderService.PlaceResult.Replayed r ->
                    ResponseEntity.ok(OrderResponse.from(r.order()));
            case OrderService.PlaceResult.ProductNotFound nf ->
                    ResponseEntity.status(422).body(new ErrorResponse("PRODUCT_NOT_FOUND",
                            "존재하지 않는 상품이 포함되어 있습니다: " + nf.missingProductIds()));
            case OrderService.PlaceResult.ProductServiceUnavailable u ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                            "PRODUCT_SERVICE_UNAVAILABLE", "잠시 후 다시 시도해 주세요."));
        };
    }

    /** 내 주문 목록, 최신순. 주문 라인은 주문 시점 스냅샷이다 — 현재 상품 정보는 클라이언트가 상품 API 로 따로 조회한다. */
    @GetMapping
    ResponseEntity<?> listMine(HttpServletRequest httpRequest,
                               @RequestParam(required = false) String cursor,
                               @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long userId = authenticatedUserId(httpRequest);
        if (userId == null) {
            return unauthenticated();
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED",
                    "size 는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다."));
        }
        Optional<Long> parsedCursor = parseCursor(cursor);
        if (cursor != null && parsedCursor.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", "cursor 값이 올바르지 않습니다."));
        }
        OrderService.OrderPage page = orderService.listMine(userId, parsedCursor, size);
        List<OrderResponse> items = page.orders().stream().map(OrderResponse::from).toList();
        return ResponseEntity.ok(new OrderPageResponse(items, page.nextCursor().map(String::valueOf).orElse(null)));
    }

    @GetMapping("/{id}")
    ResponseEntity<?> get(HttpServletRequest httpRequest, @PathVariable long id) {
        Long userId = authenticatedUserId(httpRequest);
        if (userId == null) {
            return unauthenticated();
        }
        // 남의 주문은 없는 주문과 똑같이 404 — 존재 여부도 드러내지 않는다
        return orderService.find(id)
                .filter(o -> o.getUserId() == userId.longValue())
                .<ResponseEntity<?>>map(o -> ResponseEntity.ok(OrderResponse.from(o)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("NOT_FOUND", "주문을 찾을 수 없습니다.")));
    }

    /** Gateway 가 보호 경로에서 항상 붙인다. 없으면 Gateway 를 거치지 않은 호출이다. */
    private static ResponseEntity<ErrorResponse> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHENTICATED", "로그인이 필요합니다."));
    }

    /** 커서는 클라이언트에게 불투명한 문자열이다. 지금은 주문 id 지만 형식에 기대지 말라고 계약에 적어 둔다. */
    private static Optional<Long> parseCursor(String cursor) {
        if (cursor == null) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(cursor);
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Gateway 가 붙인 회원 id. 값이 정확히 하나일 때만 믿는다 (사칭 방지, ADR 0005). */
    private static Long authenticatedUserId(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(USER_ID_HEADER));
        if (values.size() != 1) {
            return null;
        }
        try {
            return Long.valueOf(values.get(0).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
