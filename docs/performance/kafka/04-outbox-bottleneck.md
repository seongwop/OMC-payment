# 입력 경로와 독립적인 Outbox 병목

## 테스트 목적

API 입력률, Kafka consumer 수와 app vCPU를 바꾼 기존 측정에서 Outbox 발행 처리량이 함께 증가하는지 비교한다.

## 테스트 조건

- 정상 승인 100%, 각 본 측정 60초
- 실행 전 Payment·Outbox와 Kafka lag 초기화
- 입력 종료 후 모든 Outbox가 `PUBLISHED`될 때까지 관측
- API와 Kafka의 입력 처리량은 서로 다른 지표로 구분

## 측정 결과

| 입력 경로·조건 | 결제 처리량 | Outbox 처리량 | 입력 종료 후 Outbox 배출 |
| --- | ---: | ---: | ---: |
| API 100 RPS·2 vCPU | 99.81 RPS | 16.86 EPS | 295.75초 |
| API 150 RPS·2 vCPU | 149.70 RPS | 16.73 EPS | 477.48초 |
| API 200 RPS·2 vCPU | 199.61 RPS | 16.83 EPS | 653.02초 |
| API 250 RPS·2 vCPU | 247.95 RPS | 16.53 EPS | 841.32초 |
| Kafka 1/1·100 EPS·2 vCPU | 30.74 EPS | 16.65 EPS | 후속 적체 발생 |
| Kafka 10/10·250 EPS·2 vCPU | 245.64 EPS | 17.04 EPS | 후속 적체 발생 |
| Kafka 14/14·300 EPS·4 vCPU | 299.98 EPS | 17.13 EPS | 1,050.63초 |

## 결과 해석

API 입력을 2.5배 높이고 Kafka consumer를 1→14개로 늘려도 Outbox는 16.5~17.2 EPS였다. 입력 경로가 달라도 같은 범위에 머물러 polling·claim·publish 구간을 별도 병목으로 분리했다.

모든 실행에서 최종 `INIT·PUBLISHING·FAILED·DEAD=0`과 전체 `PUBLISHED`를 확인했다. 이벤트 유실은 없었지만 입력 처리량을 높일수록 최종 발행 대기시간은 길어졌다.

## 다음 단계

- `EXPLAIN ANALYZE`로 상태·재시도 시각·생성 시각 기반 polling index와 scan 범위 확인
- 다중 publisher가 겹치지 않도록 원자적 batch claim과 lease 적용
- 단건 순차 발행을 제한된 병렬 또는 batch 발행으로 전환
- 개선 전후 Outbox EPS, claim 충돌, DB CPU와 최종 drain 시간을 같은 입력 데이터로 비교

아직 개선 전 결과다. 변경 후에는 같은 입력 데이터로 Outbox EPS와 최종 drain 시간을 다시 측정한다.
