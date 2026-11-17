# devbook-order-service

devbook-commerce의 **주문** 서비스입니다. Spring Boot 4.1.1 / Java 21.

- API 계약: [devbook-platform/docs/api-contract.md](../devbook-platform/docs/api-contract.md) — 정본은 플랫폼 저장소
- 배포 매니페스트: [devbook-platform/k8s/apps/order-service.yaml](../devbook-platform/k8s/apps/order-service.yaml)

## 엔드포인트
- `POST /api/orders` 주문 생성 — `Idempotency-Key` 헤더 필수 (첫 요청 201, 같은 키 재전송 200). 주문자는 토큰의 회원(`X-User-Id`)
- `GET /api/orders?cursor=&size=` 내 주문 목록 — 최신순 커서 페이징. 라인은 주문 시점 스냅샷
- `GET /api/orders/{id}` 조회 — 본인 주문만, 남의 주문은 404

두 경로 모두 인증 필요 (Gateway가 JWT 검사 후 `X-User-Id`를 붙임).

## 개발
```bash
make wrapper   # 최초 1회: Gradle Wrapper 생성 (커밋 대상)
make test      # Docker 안에서 테스트 (로컬 JDK 불필요)
make image     # devbook/order-service:dev 이미지 빌드
```
클러스터 배포는 devbook-platform에서 `make images deploy`로 합니다.

## 설정 (환경변수)
| 이름 | 설명 |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | order-db 접속 정보 (K8s Secret `order-db-credentials`에서 주입) |
| `PRODUCT_SERVICE_URL` | 상품 서비스 주소 (클러스터 안: `http://product-service:8080`) |
