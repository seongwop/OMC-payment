# 단일 Kafka consumer 기준선

## 테스트 목적

Event Driver가 Kafka에 이벤트를 넣는 속도와 consumer가 PG 승인·DB 상태 전이까지 마치는 속도를 분리하고, 병렬성 조율 전 기준선을 확보한다.

## 테스트 조건

- payment-service 1대, 2 vCPU·8 GiB
- `order.created` 100 EPS·60초
- Kafka 1 partition·1 consumer
- 입력 종료 후 lag과 Outbox가 모두 비워질 때까지 관측

## 측정 결과

| 항목 | 결과 |
| --- | ---: |
| 실제 입력 | 6,001건 |
| Event Driver p95 | 5.52ms |
| 결제 유효 처리량 | 30.74 EPS |
| peak 관측 lag | 4,237건 |
| Outbox 처리량 | 16.65 EPS |
| 최종 Payment·Inbox·PG·Outbox | 각 6,001건 |
| 최종 lag·DLT·중복 | 0건 |

## 결과 해석

Kafka는 100 EPS를 손실 없이 수락했다. 단일 consumer는 Inbox, Redis 멱등 처리, PG 호출과 상태 전이를 마친 뒤 다음 레코드를 처리했다. 건당 약 32.5ms로 계산한 60초 적체는 약 4,156건이며, 실제 peak lag 4,237건과 비슷했다.

CPU, Hikari와 broker는 포화되지 않았다. 입력 종료 후 전량 처리된 점까지 고려해 partition 하나에 묶인 업무 처리 직렬화를 병목으로 봤다.

## 다음 단계

partition과 consumer를 함께 늘리면서 처리량, lag과 consumer당 처리시간을 비교한다.
