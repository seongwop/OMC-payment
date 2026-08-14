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

## 적용한 개선

- UNKNOWN 복구 대상을 `FOR UPDATE SKIP LOCKED` 기반 CTE로 선점하고 작업자 ID와 만료 가능한 lease 기록
- PG 조회는 짧은 claim 트랜잭션이 끝난 뒤 수행하고, 정상 종료 시 claim 해제·비정상 종료 시 lease 만료 후 재선점
- PG–DB 대사 배치는 Redis 기반 ShedLock으로 전체 인스턴스 중 하나만 실행

## 동일 조건 전후 검증

Testcontainers PostgreSQL·Redis를 공유하고 UNKNOWN 60건에 두 작업을 동시에 실행했다. 작업별 batch는 30건이며 PG 조회 지연을 동일하게 적용했다.

| 지표 | 개선 전 | 개선 후 |
| --- | ---: | ---: |
| PG 조회 | 60회 | 60회 |
| 서로 다른 PG 조회 대상 | 30건 | 60건 |
| 중복 조회 대상 | 30건 | 0건 |
| 회차 종료 후 `PAID` | 30건 | 60건 |
| 잔존 `CONFIRM_UNKNOWN` | 30건 | 0건 |
| Outbox | 30건 | 60건 |
| 대사 Job 실행 진입 | 2회 | 1회 |

외부 호출량은 같지만 개선 전에는 두 작업이 같은 30건을 조회했다. 개선 후에는 각 작업이 서로 다른 30건을 선점해 한 회차 유효 처리율이 `50%→100%`가 됐다. 추가로 lease 만료 전에는 다른 작업자가 선점하지 못하고, 만료 후에는 회수할 수 있음을 확인했다.

위 수치는 로컬 강제 중첩 통합 테스트 결과다. 실제 두 JVM·컨테이너의 처리량 수치로 사용하지 않고, 중복 작업과 단일 실행 방어 여부 비교에만 사용한다.

## 실제 2인스턴스 대사 배치 검증

현재 코드로 payment-service 컨테이너 2대를 실행하고 격리된 PostgreSQL·Redis를 공유시켰다. 두 인스턴스가 매분 0초에 동일한 실제 Spring Batch Job을 실행하도록 구성하고 `BATCH_JOB_EXECUTION`과 인스턴스별 로그를 대조했다.

| 확인 항목 | 결과 |
| --- | ---: |
| 동일 cron을 수신한 인스턴스 | 2대 |
| ShedLock 획득 | 1대 |
| 락 획득 실패로 실행 생략 | 1대 |
| 해당 cron의 Batch 실행 생성 | 1건 |
| `COMPLETED` | 1건 |
| `FAILED`·serialization failure | 0건 |
| 대사 결과 적재 | 1건 |

한 인스턴스에서는 `Locked 'paymentReconciliationJob'` 이후 실제 Job과 Step이 `COMPLETED`됐고, 다른 인스턴스에서는 `Not executing 'paymentReconciliationJob'. It's locked.`가 기록됐다. mock 기반 진입 횟수 검증을 넘어 실제 JobRepository에도 실행 한 건만 생성됨을 확인했다.
