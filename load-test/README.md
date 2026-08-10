# Load Test

| 경로 | 내용 |
| --- | --- |
| `k6` | API 승인과 Kafka Event Driver 입력 시나리오 |
| `scripts` | Prometheus 시계열, Kafka lag와 장애 구간 수집 |
| `sql` | 스케일아웃 스케줄러 경합 재현용 데이터 fixture |
| `results` | 실행별 원시 산출물, 로컬 전용 |

## Reproduction assets

| 파일 | 용도 |
| --- | --- |
| `k6/payment-wiremock-confirm.k6.js` | 고정 RPS에서 정상·timeout 승인 혼합 |
| `k6/payment-wiremock-confirm-vu.k6.js` | VU 증가로 HTTP 연결·Tomcat 포화 재현 |
| `k6/payment-order-created-event.k6.js` | Event Driver를 통한 `order.created` 고정 EPS 발행 |
| `scripts/monitor-kafka-lag.sh` | consumer group의 offset·lag·활성 인스턴스 표본 수집 |
| `scripts/collect-prometheus-dashboard.ps1` | 실행 구간의 앱·JVM·DB·Kafka 시계열 CSV 추출 |
| `scripts/build-failover-pipeline.ps1` | 인스턴스 중단 구간의 발행·소비·Payment 진행률 재구성 |
| `sql/seed-scaleout-*.sql` | UNKNOWN·TTL·대사 스케줄러의 다중 인스턴스 경합 재현 |

성능 비교 시 `RATE`, `DURATION`, timeout 비율, VM 사양, 파티션과 consumer 수를 보고서에 함께 기록합니다. 워밍업이 끝난 뒤 Payment 관련 테이블·Redis 멱등키·WireMock 요청 이력과 Kafka lag를 초기화한 시점부터 본 측정을 시작합니다.

```powershell
$env:RATE = "150"
$env:DURATION = "60s"
$env:TIMEOUT_WEIGHT = "20"
k6 run load-test/k6/payment-wiremock-confirm.k6.js
```

Kafka 경로는 Payment API가 아니라 `payment-test-tools`의 Event Driver에 이벤트를 발행합니다.

```powershell
$env:EVENT_DRIVER_BASE_URL = "http://localhost:8090"
$env:RATE = "100"
$env:DURATION = "60s"
k6 run load-test/k6/payment-order-created-event.k6.js
```

선별된 결과와 해석은 [Performance Reports](../docs/performance/README.md), 지표 정의와 비교 제약은 [측정 방법](../docs/performance/methodology.md)에 있습니다. `results`의 CSV·JSON과 로그는 Git에 올리지 않습니다.
