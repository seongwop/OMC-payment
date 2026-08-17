# OMC-payment

> 기존 프로젝트: [SOLDOUT-2/OMC](https://github.com/SOLDOUT-2/OMC)

기존 OMC MSA 팀 프로젝트에서 결제 서비스를 분리해 PG 장애 복구, 이벤트 정합성, 성능과 수평 확장을 검증한 개인 확장 프로젝트

## 기술 구성

| 구분 | 기술 |
| --- | --- |
| 애플리케이션 | Java 21, Spring Boot 3.4.5, Spring Batch, Resilience4j, Apache HttpClient5 |
| 데이터 | PostgreSQL 18, Redis 7.2 |
| 메시징 | Kafka |
| 테스트 | k6, WireMock, Testcontainers |
| 인프라 | GCP Compute Engine, Terraform, Docker Compose, GitHub Actions |
| 관측 | Prometheus, Grafana |

## 배포 구조

Compute Engine VM 4개에 애플리케이션, 데이터·메시징, 부하·관측 환경을 분리해 배포

서버별 사양과 배포 방법은 [GCP 성능 검증 환경](deploy/gcp/README.md) 참고

```text
app-vm
  - payment-service

app-2-vm (스케일아웃 테스트)
  - payment-service

infra-vm
  - PostgreSQL
  - Redis
  - Kafka / ZooKeeper
  - Toss WireMock

test-vm
  - payment-test-tools
  - k6
  - Prometheus
  - Grafana
```

서비스 컨테이너는 VM별 Docker 네트워크에 구성하고 VM 간 통신은 GCP 내부 IP로 연결

## 문서

### 테스트와 성능 개선

- [성능 테스트 진행 순서와 결과](docs/performance/README.md)
- [측정 방법과 비교 기준](docs/performance/methodology.md)
- [UNKNOWN 후속 복구와 최종 정합성](docs/performance/api/03-recovery-convergence.md)
- [Kafka 소비 병렬성 및 Outbox 병목](docs/performance/kafka/04-outbox-bottleneck.md)
- [동일 자원 스케일업·스케일아웃 비교](docs/performance/scaling/01-topology-comparison.md)
- [다중 인스턴스 스케줄러 경합](docs/performance/resilience/02-scheduler-contention.md)
