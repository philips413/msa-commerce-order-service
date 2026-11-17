package com.devbook.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.devbook.order.OrderService.OrderItemCommand;
import com.devbook.order.OrderService.PlaceResult;
import com.devbook.order.ProductClient.FetchResult;
import com.devbook.order.ProductClient.ProductSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Limit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class OrderServiceTest {

    private static final String KEY = "key-1";
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    private OrderRepository orders;
    private ProductClient productClient;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        productClient = mock(ProductClient.class);
        service = new OrderService(orders, productClient, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 같은_키로_처리된_주문이_있으면_상품을_조회하지_않고_기존_주문을_돌려준다() {
        Order existing = Order.place(KEY, 1L, List.of(new OrderLine(1L, "책", 1000L, 1)), NOW);
        when(orders.findByUserIdAndIdempotencyKey(1L, KEY)).thenReturn(Optional.of(existing));

        PlaceResult result = service.place(KEY, 1L, List.of(new OrderItemCommand(1L, 1)));

        assertThat(result).isEqualTo(new PlaceResult.Replayed(existing));
        verifyNoInteractions(productClient);
    }

    @Test
    void 없는_상품이_섞이면_주문을_저장하지_않는다() {
        when(orders.findByUserIdAndIdempotencyKey(1L, KEY)).thenReturn(Optional.empty());
        when(productClient.fetch(anyCollection()))
                .thenReturn(new FetchResult.Found(Map.of(1L, new ProductSnapshot(1L, "책", 1000L))));

        PlaceResult result = service.place(KEY, 1L,
                List.of(new OrderItemCommand(1L, 1), new OrderItemCommand(2L, 1)));

        assertThat(result).isEqualTo(new PlaceResult.ProductNotFound(Set.of(2L)));
    }

    @Test
    void 주문_금액은_주문_시점_가격과_수량의_합이다() {
        when(orders.findByUserIdAndIdempotencyKey(1L, KEY)).thenReturn(Optional.empty());
        when(productClient.fetch(anyCollection())).thenReturn(new FetchResult.Found(Map.of(
                1L, new ProductSnapshot(1L, "A", 30000L),
                2L, new ProductSnapshot(2L, "B", 12000L))));
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaceResult result = service.place(KEY, 1L,
                List.of(new OrderItemCommand(1L, 2), new OrderItemCommand(2L, 1)));

        assertThat(result).isInstanceOf(PlaceResult.Created.class);
        assertThat(((PlaceResult.Created) result).order().getTotalAmount()).isEqualTo(72000L);
    }

    @Test
    void 동시_요청으로_키_UNIQUE_위반이_나면_먼저_저장된_주문을_돌려준다() {
        Order winner = Order.place(KEY, 1L, List.of(new OrderLine(1L, "책", 1000L, 1)), NOW);
        when(orders.findByUserIdAndIdempotencyKey(1L, KEY))
                .thenReturn(Optional.empty())      // 첫 조회: 아직 없음
                .thenReturn(Optional.of(winner));  // 위반 후 재조회: 경쟁 요청이 저장한 주문
        when(productClient.fetch(anyCollection()))
                .thenReturn(new FetchResult.Found(Map.of(1L, new ProductSnapshot(1L, "책", 1000L))));
        when(orders.saveAndFlush(any(Order.class))).thenThrow(new DataIntegrityViolationException("dup"));

        PlaceResult result = service.place(KEY, 1L, List.of(new OrderItemCommand(1L, 1)));

        assertThat(result).isEqualTo(new PlaceResult.Replayed(winner));
        verify(orders).saveAndFlush(any(Order.class));
    }

    @Test
    void 상품_서비스_호출이_실패하면_저장하지_않고_Unavailable_을_돌려준다() {
        when(orders.findByUserIdAndIdempotencyKey(1L, KEY)).thenReturn(Optional.empty());
        when(productClient.fetch(anyCollection())).thenReturn(new FetchResult.Unavailable());

        PlaceResult result = service.place(KEY, 1L, List.of(new OrderItemCommand(1L, 1)));

        assertThat(result).isEqualTo(new PlaceResult.ProductServiceUnavailable());
    }

    private static OrderRepository.OrderIdView idView(long id) {
        return () -> id;
    }

    @Test
    void 목록은_size_보다_하나_더_조회해_다음_페이지가_있으면_마지막_id_를_커서로_준다() {
        when(orders.findByUserIdAndIdLessThanOrderByIdDesc(eq(1L), eq(Long.MAX_VALUE), any(Limit.class)))
                .thenReturn(List.of(idView(30L), idView(20L), idView(10L)));
        when(orders.findByIdInOrderByIdDesc(List.of(30L, 20L))).thenReturn(List.of());

        OrderService.OrderPage page = service.listMine(1L, Optional.empty(), 2);

        assertThat(page.nextCursor()).contains(20L);
        verify(orders).findByIdInOrderByIdDesc(List.of(30L, 20L));
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(orders).findByUserIdAndIdLessThanOrderByIdDesc(eq(1L), eq(Long.MAX_VALUE), limit.capture());
        assertThat(limit.getValue().max()).isEqualTo(3);
    }

    @Test
    void 마지막_페이지면_커서가_비어_있다() {
        when(orders.findByUserIdAndIdLessThanOrderByIdDesc(eq(1L), eq(20L), any(Limit.class)))
                .thenReturn(List.of(idView(10L)));
        when(orders.findByIdInOrderByIdDesc(List.of(10L))).thenReturn(List.of());

        OrderService.OrderPage page = service.listMine(1L, Optional.of(20L), 2);

        assertThat(page.nextCursor()).isEmpty();
    }

    @Test
    void 주문이_없으면_라인_조회를_하지_않는다() {
        when(orders.findByUserIdAndIdLessThanOrderByIdDesc(eq(1L), eq(Long.MAX_VALUE), any(Limit.class))).thenReturn(List.of());

        OrderService.OrderPage page = service.listMine(1L, Optional.empty(), 20);

        assertThat(page.orders()).isEmpty();
        assertThat(page.nextCursor()).isEmpty();
        verify(orders, org.mockito.Mockito.never()).findByIdInOrderByIdDesc(org.mockito.ArgumentMatchers.anyCollection());
    }
}
