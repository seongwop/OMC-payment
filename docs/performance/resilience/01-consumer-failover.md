# 스케일아웃 consumer 장애 복구

## 테스트 목적

2×2 vCPU 구성에서 payment-service 한 대가 중단됐을 때 Kafka 재조정과 남은 인스턴스의 N-1 처리 능력을 확인한다.

## 테스트 조건

- `order.created` 200 EPS, 총 12,001건 발행
- 두 인스턴스가 같은 consumer group에서 이벤트 처리
- 부하 도중 한 인스턴스를 강제 중단하고 나머지 인스턴스가 partition 인계
- 입력 종료 후 Payment·Inbox·PG·Outbox·DLT 대조

## 측정 결과

| 지표 | 결과 |
| --- | ---: |
| Event Driver 수락 | 12,001건, 실패 0건 |
| 추정 최대 적체 | 약 3,713건 |
| 입력 종료 후 마지막 Payment | 약 54.1초 |
| 마지막 Outbox 생성 후 전량 발행 | 약 543.1초 |
| 최종 Payment·unique order·Inbox·PG·Outbox | 각 12,001건 |
| 최종 lag·DLT·중복 | 0건 |

## 결과 해석

Kafka 재조정 뒤 남은 인스턴스가 전량을 처리했다. 단일 인스턴스의 30초 이동 구간 최대 처리량은 약 111.2 EPS로 입력 200 EPS보다 낮았고, 장애 이후 backlog는 입력이 끝날 때까지 증가했다.

peak lag 수집 프로세스가 제한 시간 안에 종료되지 않아 3,713건은 k6 누적 발행과 listener counter를 결합한 추정치다. 이 수치를 정확한 Kafka 순간 최대 lag으로 표현하지 않는다.

## 다음 단계

- N-1 목표 입력률을 100 EPS 수준부터 다시 측정
- 장애 시점의 Kafka lag 수집을 별도 프로세스로 보완
- 전체 처리량이 아닌 장애 후 backlog 회복 시간을 스케일아웃 용량 기준에 포함
