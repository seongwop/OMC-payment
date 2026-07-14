# Payment Service

## 개요
결제 처리 및 환불을 담당하는 마이크로서비스입니다.
SAGA 패턴을 통해 분산 트랜잭션의 일관성을 보장합니다.

## 포트
- `8086`

## 주요 기능
- 결제 처리 (`POST /api/payments`)
- 결제 취소/환불
- SAGA Choreography 패턴으로 보상 트랜잭션 처리
- 결제 상태: `PENDING` → `COMPLETED` / `FAILED` / `REFUNDED`

## 기술 스택
- Spring Boot 3.2.x
- Spring Data JPA
- Spring Kafka (Consumer/Producer)
- PostgreSQL 18
- Eureka Client

## SAGA 흐름
```
order.created → [payment-service] → 결제 처리
   → 성공: payment.completed → [order-service] 주문 확정
   → 실패: payment.failed   → [order-service] 주문 취소
```

## Kafka Topics
| Topic | 역할 | 설명 |
|-------|------|------|
| `order.created` | Consumer | 주문 생성 시 결제 시작 |
| `payment.completed` | Producer | 결제 성공 이벤트 |
| `payment.failed` | Producer | 결제 실패 → 보상 트랜잭션 |
