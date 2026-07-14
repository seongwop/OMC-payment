# OMC Payment Resilience Lab

OMC 팀 프로젝트에서 구현한 Payment Service를 기준으로 결제 정합성, PG 장애 복구,
성능 격리와 수평 확장을 개인적으로 검증하기 위해 분리한 실험 프로젝트입니다.

## Included

- Payment 상태 머신과 결제 승인·취소 처리
- Kafka Inbox/Outbox와 Redis 멱등성
- CONFIRM_UNKNOWN/CANCEL_UNKNOWN 후속 복구
- 결제 만료 스케줄러와 PG-DB 대조 배치
- Toss API를 재현하는 WireMock 시나리오
- k6 부하 테스트와 Prometheus/Grafana 관측 환경

## Local Run

```bash
./gradlew :payment-service:bootJar
docker compose up -d --build
```

서비스 주소:

- Payment API: `http://localhost:8085`
- Toss WireMock: `http://localhost:18080`
- Prometheus: `http://localhost:19090`
- Grafana: `http://localhost:13000`

## Load Test

```bash
k6 run load-test/k6/payment-wiremock-confirm.k6.js
k6 run load-test/k6/payment-wiremock-confirm-vu.k6.js
```

## Migration Boundary

`team-project-baseline` 태그까지는 OMC 팀 프로젝트의 Payment Service 기준 코드이며,
이후 커밋부터 개인 배포·성능·정합성 개선 이력을 기록합니다.
