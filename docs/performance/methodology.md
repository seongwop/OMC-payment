# 측정 방법과 판정 기준

## 공통 테스트 조건

- GCP의 payment-service와 WireMock PG, PostgreSQL, Kafka, Redis를 사용
- API와 Event Driver 모두 k6 `constant-arrival-rate`로 본 측정 60초 수행
- 재기동 시 보정 부하로 JVM JIT를 안정화한 뒤 무부하 냉각
- 각 본 측정 전 Payment·Inbox·Outbox, Redis 멱등키, WireMock journal과 Kafka lag 초기화
- verifier는 부하 중 비활성화하고 입력 종료 후 DB·Kafka·PG 호출을 대조

## 처리량 지표

| 지표 | 의미 |
| --- | --- |
| API 완료 RPS | 결제 HTTP 요청이 응답까지 완료된 속도 |
| Event Driver EPS | Kafka producer가 이벤트 발행을 수락한 속도 |
| 결제 유효 처리량 | 첫 Payment 생성부터 마지막 최종 상태까지의 업무 처리 속도 |
| Outbox 유효 처리량 | 첫 Outbox 생성부터 마지막 `PUBLISHED`까지의 발행 속도 |

Event Driver p95는 Kafka 발행 요청이 수락된 시간이다. 이벤트 처리 결과는 Payment 상태 시각, committed offset 기반 lag과 최종 DB 건수로 판정했다.

## 합격 기준

- 부하 구간: 목표 입력률, dropped iteration, p95, Bulkhead 거절과 자원 포화 확인
- 소비 구간: 결제 유효 처리량, peak 관측 lag와 입력 종료 후 drain 시간 확인
- 최종 정합성: Payment·Inbox·PG 호출·Outbox·완료 이벤트 건수 대조
- 종료 조건: 미확정 상태, Outbox 미발행, Kafka lag, DLT와 중복이 모두 0인지 확인

## 결과 해석 기준

- peak lag는 주기적 표본의 관측 최대값이며 정확한 순간 최대값은 아님
- JIT가 큰 첫 실행은 설정 비교에서 제외하고 같은 JVM의 안정화 실행을 대표값으로 사용
- 4 partitions·150 EPS 두 번째 실행은 고빈도 관측 자체가 처리에 영향을 준 observer effect 사례로 남기고 공식 처리량에서는 제외
- 단일 Kafka broker와 PostgreSQL을 공유하므로 앱 확장 결과에는 공유 인프라 경합이 포함됨
- 입력을 모두 처리한 결과와 해당 구성의 최대 처리 한계를 구분
