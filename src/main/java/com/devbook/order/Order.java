package com.devbook.order;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
// 멱등성 키는 회원 단위로 유일하다 (V2). 다른 회원의 키로 남의 주문을 꺼내 볼 수 없게 한다
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    /** 원(KRW) 단위 */
    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ElementCollection
    @CollectionTable(name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {
        // JPA
    }

    static Order place(String idempotencyKey, long userId, List<OrderLine> lines, Instant now) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("order must have at least one line");
        }
        Order order = new Order();
        order.idempotencyKey = idempotencyKey;
        order.userId = userId;
        order.status = OrderStatus.CREATED;
        order.createdAt = now;
        order.lines = new ArrayList<>(lines);
        long total = 0;
        for (OrderLine line : lines) {
            total = Math.addExact(total, line.amount());
        }
        order.totalAmount = total;
        return order;
    }

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public long getTotalAmount() { return totalAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderLine> getLines() { return List.copyOf(lines); }
}
