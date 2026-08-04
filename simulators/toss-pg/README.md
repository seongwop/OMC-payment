# Toss PG WireMock

실제 Toss Payments를 호출하지 않고 승인·조회·취소의 정상 응답과 네트워크 장애를 결정적으로 재현하는 테스트 전용 대역입니다. `providerPaymentId` 또는 `paymentKey`의 prefix로 시나리오를 선택하며 PG 데이터를 영속화하지 않습니다.

## Access

| 환경 | 주소 |
| --- | --- |
| 로컬 호스트 | `http://localhost:18080` |
| Docker Compose 내부 | `http://toss-wiremock:8080` |
| GCP App VM | `http://10.20.0.20:18080` |

관리 API:

- `GET /__admin/mappings`: 등록된 시나리오 확인
- `GET /__admin/requests`: 실제 PG 호출 횟수와 요청 확인
- `DELETE /__admin/requests`: 테스트 간 요청 이력 초기화

WireMock mapping은 `test-secret-key`의 Basic Auth 값을 요구합니다. 이 값은 실제 PG credential이 아닌 로컬·GCP 격리 테스트 전용 고정값입니다.

## Core scenarios

| key에 포함할 문자열 | 승인 또는 취소 | 후속 조회 | 검증 목적 |
| --- | --- | --- | --- |
| `success` | 승인 `DONE` | `DONE` | 정상 승인 |
| `card-limit` | 400 business error | - | 명확한 결제 실패 |
| `server-error` | 500 | 기본 `DONE` | PG 서버 오류 |
| `network-error` | connection reset | 기본 `DONE` | 승인 결과 불명확 |
| `approved-but-timeout` | 5초 후 `DONE` | `DONE` | PG 승인 완료 후 응답 유실 |
| `approved-but-reset` | connection reset | `DONE` | 승인 완료 가능성이 있는 연결 단절 |
| `not-approved-timeout` | 5초 후 504 | 404 | 실제 승인되지 않은 timeout |
| `failed-after-timeout` | timeout | `ABORTED` | 재조회 후 실패 보정 |
| `pending-long-timeout` | timeout | `IN_PROGRESS` | 복구 재시도와 한도 초과 |
| `canceled-after-timeout` | timeout | `CANCELED` | 조회를 통한 취소 상태 보정 |
| `lookup-timeout` | timeout | 5초 지연 | PG 조회 자체의 timeout |
| `lookup-rate-limit` | timeout | 429 | 조회 retry와 격리 |
| `lookup-server-error` | timeout | 500 | 조회 서버 오류 |
| `cancel-timeout` | 취소 5초 지연 | - | `CANCEL_UNKNOWN` 전이 |
| `cancel-reset-recovered-canceled` | 취소 connection reset | `CANCELED` | 취소 결과 후속 수렴 |

세부 URL, priority와 응답 본문은 [`mappings`](mappings) JSON을 기준으로 합니다.

## Usage

결제 승인 요청의 `providerPaymentId`에 시나리오 prefix와 고유 주문 ID를 결합합니다.

```json
{
  "orderID": "0190f7d3-5000-7000-8000-000000000001",
  "providerPaymentId": "mock-approved-but-timeout-0190f7d3-5000-7000-8000-000000000001",
  "originalAmount": 10000,
  "discountAmount": 0,
  "finalAmount": 10000
}
```

혼합 비율은 README에 고정하지 않고 k6 환경 변수로 명시합니다.

```powershell
$env:SUCCESS_WEIGHT = "80"
$env:APPROVED_TIMEOUT_WEIGHT = "20"
$env:DURATION = "60s"
k6 run load-test/k6/payment-wiremock-confirm.k6.js
```

테스트가 끝나면 Payment 최종 상태와 함께 WireMock 승인·조회·취소 횟수, 상태 이력, Outbox와 중복 `orderId`를 대조합니다. 검증되지 않은 webhook이나 실제 Toss 동작은 이 시뮬레이터의 범위에 포함하지 않습니다.
