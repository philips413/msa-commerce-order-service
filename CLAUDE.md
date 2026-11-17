# devbook-order-service

devbook-commerce의 주문 서비스. 전체 맥락·결정은 `../devbook-platform/CLAUDE.md`와 `../devbook-platform/docs/adr/`.

## 하드 제약
- API 계약 정본은 `../devbook-platform/docs/api-contract.md`. 계약을 바꾸려면 그 문서부터 고친다(이 저장소 단독으로 바꾸지 않는다).
- 이 서비스는 **자기 DB(orderdb)만** 쓴다. 다른 서비스 데이터는 API로만 묻는다.
- 다른 서비스와 코드 공유 모듈을 만들지 않는다. `ErrorResponse` 등 중복은 의도된 것.
- 금액은 `long`(원), 시각은 `Instant`(UTC, DB 정밀도에 맞춰 마이크로초).
- JPA `ddl-auto: validate`. 스키마 변경은 새 Flyway 파일로만 (기존 마이그레이션 수정 금지).
- 시크릿을 저장소에 넣지 않는다.
- 주문 생성 멱등성: `idempotency_key` UNIQUE 제약이 최종 방어선. `OrderService.place`에 `@Transactional`을 붙이지 않는다(상품 서비스 호출 중 DB 커넥션 점유 방지).
- 주문 라인의 `title`·`unitPrice`는 주문 시점 스냅샷이다.
- 조회 경로(목록·단건)에서 상품 서비스를 호출하지 않는다. 현재 상품 정보는 클라이언트가 합친다 (장애 격리).
- 목록 조회는 "id 커서로 자르기 → 그 id 들을 라인과 함께 읽기" 2단계다. 컬렉션 fetch 와 LIMIT 를 한 쿼리에 섞지 않는다(메모리 페이징).
- 주문자는 `X-User-Id` 헤더(Gateway가 JWT에서 만든 값)로만 정한다. 본문으로 회원 id를 받지 않는다. 남의 주문 조회는 404 (ADR 0005).

## 명령
`make test`, `make image` (Docker 안에서 빌드).
