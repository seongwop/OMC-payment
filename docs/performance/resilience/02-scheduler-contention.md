# 다중 인스턴스 스케줄러 경합 재현

## 테스트 목적

두 payment-service가 같은 DB를 대상으로 UNKNOWN 복구, READY TTL 만료와 PG–DB 대사를 동시에 실행할 때 작업이 중복되는지 확인한다.

## 테스트 조건

- payment-service 2대, 인스턴스별 scheduler 활성화
- UNKNOWN 복구는 느린 PG 조회와 SQL fixture로 실행 구간 강제 중첩
- READY 만료 400건, PG–DB 불일치 20건 사전 생성
- 실행 후 PG journal, 상태 이력, Outbox와 Spring Batch JobRepository 대조

## 측정 결과

| 시나리오 | 기대 | 실제 결과 | 판정 |
| --- | ---: | ---: | --- |
| 자연 분산 UNKNOWN 복구 | timeout 1,243건 조회 | PG 조회 1,243회, 전량 `PAID` | 실행 시점이 어긋나 정상 |
| UNKNOWN 강제 중첩 | PG 조회 60회 | 70회, +16.7%, 낙관적 락 충돌 1건 | 중복 작업 재현 |
| READY TTL 만료 | 400건 만료 | `FAILED`·이력·Outbox 각 400건 | 결과 정상, 소유권 미보장 |
| PG–DB 대사 동시 실행 | 불일치 20건 처리 | 한 인스턴스 완료, 다른 인스턴스 Job 생성 실패 | Batch 경합 재현 |

## 결과 해석

UNKNOWN 복구는 후보 조회와 작업 소유권 획득이 분리돼 있었다. 두 인스턴스가 같은 행을 읽은 뒤 각각 PG를 조회하고, DB 변경 단계에서 `@Version` 충돌이 발생했다. 낙관적 락으로 중복 상태 변경은 막았지만 외부 조회는 이미 중복 실행됐다.

PG–DB 대사는 동일 cron에 두 인스턴스가 Spring Batch Job을 생성하면서 JobRepository의 PostgreSQL serialization failure가 발생했다.

## 다음 단계

- UNKNOWN·TTL 대상에 원자적 batch claim과 만료 가능한 lease 적용
- PG 호출은 claim 트랜잭션 밖에서 수행하고 항목별 예외 격리
- reconciliation은 ShedLock 또는 DB advisory lock으로 단일 실행 보장
- 같은 강제 중첩 조건에서 PG 중복 조회율, 충돌 수와 회차 완료율을 전후 비교

현재 문서는 개선 전 재현 결과다. 변경 후 같은 fixture와 실행 시각으로 다시 측정한다.
