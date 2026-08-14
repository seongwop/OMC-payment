# 성능 테스트 결과

결제 API의 안정 구간 탐색부터 장애 혼입, Kafka 소비 병렬성 조율, 스케일업·스케일아웃과 다중 인스턴스 정합성 검증까지 순서대로 정리했다. 각 문서는 앞선 결과를 기준으로 다음 테스트 조건을 정한 흐름대로 배열했다.

## 테스트 순서

| 단계 | 조건 변경 | 확인한 내용 | 보고서 |
| ---: | --- | --- | --- |
| 1 | 정상 승인 100→300 RPS | 단일 2 vCPU API의 안정 구간과 포화 전환점 | [정상 API 부하 단계 측정](api/01-normal-load-ramp.md) |
| 2 | 승인 timeout 20% 혼합 | 정상 요청 영향, Bulkhead 거절과 UNKNOWN 생성 | [timeout 혼합 부하 단계 측정](api/02-timeout-load-ramp.md) |
| 3 | 부하 종료 후 복구 관측 | UNKNOWN·Outbox가 최종 상태까지 수렴하는 시간 | [후속 복구와 최종 정합성](api/03-recovery-convergence.md) |
| 4 | Kafka 1 partition·1 consumer | 이벤트 입력 수락과 실제 결제 처리량 분리 | [단일 consumer 기준선](kafka/01-single-consumer-baseline.md) |
| 5 | 1→16 partitions·consumers | 2 vCPU에서 병렬성 증가 효과와 수익 체감 확인 | [2 vCPU 소비 병렬성 조율](kafka/02-concurrency-tuning-2vcpu.md) |
| 6 | 2→4 vCPU, 10→16 consumers | CPU 추가 후 병렬성을 다시 조율해 300 EPS 추종 | [4 vCPU 스케일업 재조율](kafka/03-scaleup-tuning-4vcpu.md) |
| 7 | API·Kafka·인프라 조건 교차 비교 | 입력 경로와 무관한 Outbox 고정 병목 확인 | [Outbox 후속 병목](kafka/04-outbox-bottleneck.md) |
| 8 | 1×4 vCPU↔2×2 vCPU | 동일 총 자원의 수직·수평 배치 비교 | [동일 자원 토폴로지 비교](scaling/01-topology-comparison.md) |
| 9 | consumer 인스턴스 강제 중단 | N-1 처리 용량과 최종 정합성 확인 | [consumer 장애 복구](resilience/01-consumer-failover.md) |
| 10 | 다중 스케줄러 강제 중첩 | UNKNOWN 중복 조회·Batch 경합 재현과 방어 구현 검증 | [스케줄러 경합 재현](resilience/02-scheduler-contention.md) |

측정값을 읽는 기준과 비교 제약은 [측정 방법](methodology.md)에 정리했다.

## 문서 보관 기준

- Git 공개: 재현 스크립트, 단계별 핵심 보고서와 비교 결론
- 로컬 보관: 날짜별 원본 보고서 39개(`local-artifacts/performance-reports/original`)
- 로컬 보관: k6 JSON, Prometheus·Grafana CSV, Kafka lag와 DB 검증 산출물(`load-test/results`)
- 원본과 산출물은 삭제하지 않고 `.gitignore`로 공개 저장소에서만 제외

WireMock은 장애 패턴 재현용 PG 대역이다. 문서의 수치는 실제 Toss PG 성능을 의미하지 않는다.
