# Toss WireMock

결제 서비스가 실제 Toss Payments 대신 호출할 수 있는 로컬 PG Mock 서버를 구축한다.

목표는 부하테스트 중 실제 운영에서 발생할 수 있는 PG 장애를 결정적으로 재현하는 것이다.

## 전체 목표

부하테스트 중 빌링키 발급, 결제 승인, 결제 취소 요청을 지속적으로 발생시킨다.

요청 중 일부는 `providerPaymentId` 또는 `paymentKey` 값으로 장애 시나리오를 선택한다.

Payment Service는 Toss Adapter를 통해 WireMock을 호출한다.

WireMock은 선택된 key에 따라 성공, PG 요청 실패, timeout, connection reset, 승인 후 응답 유실, 취소 실패 등을 재현한다.

테스트 종료 후 DB, Outbox, DLT, PG 조회 결과를 대조해 중복 결제, 미확정 결제, 상태 불일치가 없는지 검증한다.

## 역할 분리

| 구성요소            | 역할 |
|-----------------| --- |
| JMeter/k6       | 부하 발생 및 장애 key 비율 제어 |
| paymentKey      | 어떤 PG 시나리오를 실행할지 선택하는 스위치 |
| WireMock        | paymentKey를 보고 실제 PG 응답 또는 장애를 결정적으로 재현 |
| Payment Service | PG 응답을 처리하고 Payment, Outbox, 상태 전이를 저장 |
| 검증 쿼리/API       | 테스트 종료 후 결제 무결성 확인 |

## 실행 주소

Docker 컨테이너 내부에서 payment-service가 호출할 주소:

```text
http://toss-wiremock:8080
```

로컬 IntelliJ, Postman, 브라우저에서 확인할 주소:

```text
http://localhost:18080
```

관리 API:

```text
http://localhost:18080/__admin/mappings
http://localhost:18080/__admin/requests
```

## 인증

Toss Payments는 Secret Key 기반 Basic Auth를 사용한다.

WireMock도 테스트 정확도를 위해 아래 Authorization 값을 요구한다.

```text
Authorization: Basic dGVzdC1zZWNyZXQta2V5Og==
```

이 값은 아래 문자열을 Base64 인코딩한 값이다.

```text
test-secret-key:
```

payment-service를 WireMock Toss 모드로 실행할 때는 아래 설정을 사용한다.

```text
PAYMENT_PG_MODE=toss
TOSS_BASE_URL=http://localhost:18080
TOSS_SECRET_KEY=test-secret-key
```

Docker 내부 payment-service 컨테이너에서는 `TOSS_BASE_URL`을 아래처럼 둔다.

```text
TOSS_BASE_URL=http://toss-wiremock:8080
```

## 표준 paymentKey 시나리오

아래 key 중 하나를 `providerPaymentId`로 넣어 장애 비율을 제어한다.

| paymentKey | confirm 결과 | lookup 결과 | 목적 |
| --- | --- | --- | --- |
| `mock-success-payment-key` | 200 DONE | DONE | 정상 승인 |
| `mock-card-limit-payment-key` | 400 EXCEED_MAX_CARD_LIMIT | DONE | 카드 한도 초과 등 PG 비즈니스 실패 |
| `mock-server-error-payment-key` | 500 INTERNAL_SERVER_ERROR | DONE | PG 서버 오류 |
| `mock-timeout-payment-key` | 5초 지연 후 DONE | DONE | 단순 느린 PG 응답 |
| `mock-network-error-payment-key` | connection reset | DONE | 승인 여부를 알 수 없는 네트워크 단절 |
| `mock-approved-but-timeout-payment-key` | 5초 지연 후 DONE | DONE | PG는 승인했지만 우리 서버가 응답을 못 받은 상황 |
| `mock-approved-but-reset-payment-key` | connection reset | DONE | PG는 승인했지만 연결만 끊긴 상황 |
| `mock-not-approved-timeout-payment-key` | 5초 지연 후 실패 | 404 NOT_FOUND | PG 승인 자체가 없었던 timeout |
| `mock-cancel-timeout-payment-key` | 승인 성공 | 취소 5초 지연 | 취소 요청 timeout |
| `mock-cancel-server-error-payment-key` | 승인 성공 | 취소 500 | 취소 요청 PG 오류 |
| `mock-cancel-reset-recovered-canceled-payment-key` | 승인 성공 | 취소 connection reset 후 조회 CANCELED | `CANCEL_UNKNOWN` 최종 상태 수렴 |

## JMeter 장애 비율 예시

JSR223 PreProcessor에서 `paymentKey` 변수를 아래처럼 만들 수 있다.

```groovy
def r = Math.random()
def key

if (r < 0.80) {
    key = 'mock-success-payment-key'
} else if (r < 0.85) {
    key = 'mock-server-error-payment-key'
} else if (r < 0.90) {
    key = 'mock-timeout-payment-key'
} else if (r < 0.95) {
    key = 'mock-network-error-payment-key'
} else {
    key = 'mock-card-limit-payment-key'
}

vars.put('paymentKey', key)
```

HTTP Request body에서는 아래처럼 사용한다.

```json
{
  "orderID": "${__UUID()}",
  "dropId": "33333333-3333-3333-3333-333333333333",
  "productId": "44444444-4444-4444-4444-444444444444",
  "providerPaymentId": "${paymentKey}",
  "couponID": null,
  "originalAmount": 10000,
  "discountAmount": 0,
  "finalAmount": 10000
}
```

## JMeter 부하테스트 프로파일

모든 시나리오 key를 하나의 confirm 요청 비율에 섞지 않는다.

결제 승인, UNKNOWN 복구, 결제 취소는 목적과 선행 데이터가 다르므로 테스트 플랜을 분리한다.

### Profile A: Confirm Mixed

목적은 일반 결제 승인 요청 중 성공, PG 비즈니스 실패, PG 장애가 섞여 들어오는 상황을 확인하는 것이다.

```text
80% mock-success-payment-key
5%  mock-card-limit-payment-key
5%  mock-server-error-payment-key
5%  mock-timeout-payment-key
5%  mock-network-error-payment-key
```

### Profile B: UNKNOWN Recovery

목적은 PG 승인 여부가 애매한 결제들이 남았을 때 재조회, 망취소, 웹훅, 정산 배치가 필요한 이유를 확인하는 것이다.

```text
60% mock-success-payment-key
15% mock-approved-but-timeout-payment-key
10% mock-approved-but-reset-payment-key
10% mock-not-approved-timeout-payment-key
5%  mock-network-error-payment-key
```

### Profile C: Cancel Compensation

목적은 이미 PAID 상태가 된 결제를 취소하거나 보상 트랜잭션으로 취소할 때 PG 취소 장애를 확인하는 것이다.

이 프로파일은 먼저 confirm 성공 요청으로 PAID 결제를 만든 뒤 cancel API를 호출한다.

WireMock은 결제 데이터를 저장하지 않으므로, 실제 취소 가능 여부는 payment-service DB의 PAID Payment가 보장한다.

WireMock은 providerPaymentId 문자열을 보고 취소 요청의 PG 장애만 재현한다.

```text
80% mock-success-payment-key
10% mock-cancel-timeout-payment-key
10% mock-cancel-server-error-payment-key
```

### Profile D: Long Running Chaos

목적은 긴 시간 동안 결제 승인, 빌링키 발급, 결제 취소 요청이 섞인 상태에서도 최종 미스가 없는지 확인하는 것이다.

초기에는 장애 비율을 높게 유지해 문제를 빠르게 관측하고, 안정화 이후에는 운영에 가까운 낮은 장애 비율로 조정한다.

```text
90% normal flow
3%  PG business failure
3%  PG timeout
2%  network error
2%  cancel failure
```

## 개선사항

| 개선사항 | 재현 시나리오 |
| --- | --- |
| HTTP timeout 설정 | `mock-timeout-payment-key`가 timeout으로 실패하지 않고 느린 성공으로 끝나는지 확인 |
| UNKNOWN 후속 재처리 | `mock-approved-but-timeout-payment-key` 후 DB는 UNKNOWN, PG lookup은 DONE인 상태 확인 |
| 망취소 | PG lookup은 DONE인데 우리 DB가 UNKNOWN 또는 실패인 결제 확인 |
| 웹훅 | 서버가 응답을 못 받은 결제를 PG가 나중에 알려줄 통로가 없는지 확인 |
| 불변 감사 로그 | 상태 전이가 최종 상태만 남고 이력 추적이 어려운지 확인 |
| 외부 PG 재시도 | 일시적 500, connection reset이 단발 실패로 끝나는지 확인 |
| 서킷 브레이커 | PG 장애가 반복되어도 계속 외부 호출을 시도하는지 확인 |
| PENDING 만료 스케줄러 | 미완료 결제가 TTL 이후에도 방치되는지 확인 |
| 대조 및 정산 배치 | DB 상태와 PG lookup 상태가 달라도 자동 탐지가 없는지 확인 |
| Rate Limiter | 대량 요청이 제한 없이 payment-service와 PG Mock으로 전달되는지 확인 |

## 테스트 종료 후 검증 예시

```sql
SELECT count(*)
FROM payment_db.p_payments
WHERE payment_status = 'UNKNOWN';

SELECT count(*)
FROM payment_db.p_payments
WHERE payment_status IN ('PENDING', 'CONFIRMING');

SELECT order_id, count(*)
FROM payment_db.p_payments
GROUP BY order_id
HAVING count(*) > 1;

SELECT count(*)
FROM payment_db.p_payment_outbox_events
WHERE status = 'FAILED';
```
