# Payment Service

결제 승인·취소와 PG 응답 불명확 구간의 후속 복구를 담당하는 Spring Boot 서비스입니다. API와 Kafka 입력은 동일한 결제 상태 머신을 사용하고, DB 상태 변경과 후속 이벤트는 Transactional Outbox로 함께 저장합니다.

## State machine

```text
READY → CONFIRMING → PAID → CANCELED
  │          │          └→ CANCEL_UNKNOWN → CANCELED | PAID | RECOVERY_FAILED
  │          └→ CONFIRM_UNKNOWN → PAID | FAILED | CANCELED | RECOVERY_FAILED
  └→ FAILED | CANCELED
```

- `CONFIRM_UNKNOWN`: 승인 요청이 PG에 도달했을 가능성이 있지만 응답을 확정할 수 없는 상태
- `CANCEL_UNKNOWN`: 취소 요청 결과를 확정할 수 없는 상태
- `RECOVERY_FAILED`: 자동 복구 한도를 초과해 대사 또는 운영 확인이 필요한 격리 상태

## Main flows

| 흐름 | 구현 |
| --- | --- |
| 승인·취소 | Toss adapter, 상태 전이 검증, JPA optimistic lock |
| 중복 방어 | Redis request idempotency와 Kafka Inbox |
| 이벤트 소비 | manual ack, 예외별 retry와 topic별 DLT |
| 이벤트 발행 | Outbox 저장, 조건부 `PUBLISHING` claim, retry와 `DEAD` 격리 |
| UNKNOWN 복구 | PG lookup, 상태 보정, 필요 시 network cancellation |
| 장기 미완료 | TTL expiration scheduler |
| 잔여 불일치 | Spring Batch 기반 PG–DB reconciliation 결과 적재 |
| PG 자원 보호 | Apache HttpClient5 pool, timeout, Resilience4j Bulkhead·Retry |

## Interfaces

- `POST /internal/v1/payments/confirm`: 내부 결제 승인
- `POST /internal/v1/payments/pre-auth`: 결제 사전 생성
- `POST /api/v1/payments/{paymentId}/cancel`: 결제 취소
- Kafka input: `order.created`, `refund.requested`, `stock.failed`
- Kafka output: `payment.completed`, `payment.failed`, `refund.done`

서버 포트는 `8085`입니다.

## Stack

Java 21, Spring Boot 3.4.5, Spring Data JPA, Spring Kafka, Spring Batch, PostgreSQL, Redis, Flyway, Apache HttpClient5, Resilience4j, Micrometer

테스트 입력과 정합성 확인 방법은 [Payment Test Tools](../payment-test-tools/README.md), 측정 결과는 [Performance Reports](../docs/performance/README.md)를 참고합니다.
