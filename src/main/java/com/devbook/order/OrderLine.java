package com.devbook.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** 주문 시점의 상품 스냅샷. 값 객체라 식별자가 없다. */
@Embeddable
public class OrderLine {

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private String title;

    /** 원(KRW) 단위 */
    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    protected OrderLine() {
        // JPA
    }

    public OrderLine(long productId, String title, long unitPrice, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        this.productId = productId;
        this.title = title;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    long amount() {
        return Math.multiplyExact(unitPrice, quantity);
    }

    public long getProductId() { return productId; }
    public String getTitle() { return title; }
    public long getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
}
