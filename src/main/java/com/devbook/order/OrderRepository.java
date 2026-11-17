package com.devbook.order;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<Order, Long> {

    // open-in-view=false 이므로 응답 조립 전에 lines 를 함께 읽는다
    @EntityGraph(attributePaths = "lines")
    Optional<Order> findByUserIdAndIdempotencyKey(long userId, String idempotencyKey);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findWithLinesById(long id);

    /** 목록 1단계: id 만 커서 기준으로 자른다. (컬렉션 fetch 와 LIMIT 를 한 쿼리에 쓰면 메모리에서 페이징된다) */
    List<OrderIdView> findByUserIdAndIdLessThanOrderByIdDesc(long userId, long id, Limit limit);

    /** 목록 2단계: 잘라 낸 id 의 주문을 라인까지 한 번에 읽는다 (N+1 회피). */
    @EntityGraph(attributePaths = "lines")
    List<Order> findByIdInOrderByIdDesc(Collection<Long> ids);

    interface OrderIdView {
        Long getId();
    }
}
