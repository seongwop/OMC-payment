# Payment Test Tools

`payment-test-tools`는 결제 서비스의 로컬 검증을 위한 테스트 전용 애플리케이션입니다.
상위 서비스 대신 결제 입력 이벤트를 발행하고 Payment DB, Outbox, Kafka 발행 이벤트의 정합성을 검증합니다.

## 실행

```powershell
.\gradlew.bat :payment-service:bootJar :payment-test-tools:bootJar
docker compose up -d --build
```

접속 주소:

- 결제 서비스: `http://localhost:8085`
- 결제 테스트 도구: `http://localhost:8090`
- 상태 확인: `http://localhost:8090/actuator/health`

## order.created 이벤트 발행

```http
POST /internal/test/events/order-created
Content-Type: application/json

{
  "orderId": "0190f7d3-5000-7000-8000-000000000001",
  "userId": "0190f7d3-5000-7000-8000-000000000002",
  "orderType": "DROP",
  "dropId": "0190f7d3-5000-7000-8000-000000000003",
  "productId": "0190f7d3-5000-7000-8000-000000000004",
  "originalAmount": 10000,
  "discountAmount": 0,
  "finalAmount": 10000,
  "providerPaymentId": "mock-approved-but-timeout-0190f7d3-5000-7000-8000-000000000001"
}
```

`eventId`는 선택값이며 생략하면 Event Driver가 테스트용 ID를 생성합니다.
`providerPaymentId`도 선택값입니다. 생략하면 기존처럼 `orderId` 문자열을 사용하고,
값을 전달하면 Toss WireMock의 성공·timeout·connection reset 시나리오를 선택하는 데 사용합니다.

다른 입력 이벤트 API:

- `POST /internal/test/events/refund-requested`
- `POST /internal/test/events/stock-failed`

## 정합성 검증

현재 상태를 즉시 조회합니다.

```http
GET /internal/test/verifications/orders/{orderId}
```

최종 판정이 나오거나 제한 시간이 만료될 때까지 조회합니다.

```http
GET /internal/test/verifications/orders/{orderId}/await?timeoutMs=15000&pollIntervalMs=200
```

검증 결과:

- `CONSISTENT`: DB 최종 상태와 발행 이벤트가 일치합니다.
- `PENDING`: 결제 복구 또는 Outbox 발행이 진행 중입니다.
- `REQUIRES_ATTENTION`: 복구 또는 Outbox 재시도 횟수를 초과해 운영자 확인이 필요합니다.
- `INCONSISTENT`: DB 최종 상태에 대응하는 이벤트가 없거나 상충하는 이벤트가 관찰됐습니다.
- `NOT_FOUND`: Payment 데이터가 아직 생성되지 않았습니다.

Kafka 이벤트 관찰 결과는 테스트 도구의 로컬 메모리에 저장됩니다. 독립된 테스트 실행 전 다음 API로 초기화합니다.

```http
DELETE /internal/test/verifications/observations
```

## 성능 테스트 모드

Verifier는 Kafka 결과 이벤트를 소비하고 검증 요청마다 Payment 및 Outbox 테이블을 조회합니다.
처리량과 응답시간을 측정할 때는 다음 환경 변수로 Verifier를 끄고 Event Driver만 사용합니다.

```env
TEST_TOOLS_VERIFIER_ENABLED=false
```

이 모드에서는 Verifier Kafka Consumer와 검증 API가 모두 비활성화됩니다. 테스트 도구의 헬스체크가
공유 DB를 조회하지 않도록 DB Health Indicator도 함께 비활성화됩니다. 정합성 테스트 또는 부하 테스트
종료 후 검증할 때는 값을 `true`로 되돌립니다.
