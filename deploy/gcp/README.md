# GCP Benchmark Environment

실제 PG 대신 WireMock을 사용하는 성능·정합성 검증 전용 환경입니다. 모든 VM은 고정 private IP만 사용하고 운영자 SSH는 IAP로 제한합니다.

## Topology

| VM | 사양 | 역할 |
| --- | --- | --- |
| `omc-payment-app-vm` | `e2-standard-2` | payment-service app-1 |
| `omc-payment-app-2-vm` | `e2-standard-2` | 스케일아웃 비교용 app-2 |
| `omc-payment-infra-vm` | `e2-standard-4` | PostgreSQL, Redis, Kafka, ZooKeeper, WireMock |
| `omc-payment-test-vm` | `e2-standard-2` | k6, Event Driver, Prometheus, Grafana |

기본 프로비저닝 합계는 10 vCPU입니다. PostgreSQL과 Kafka·ZooKeeper는 서로 다른 persistent disk를 사용하고, 서비스 VM은 상태를 저장하지 않습니다.

API 부하는 app-1을 직접 호출합니다. 두 App VM 비교는 load balancer가 없는 현재 구성에서 Kafka consumer group을 통해 입력을 분산하므로, HTTP 수평 확장 결과로 해석하지 않습니다.

## Deploy

단일 App 기준 측정은 다음 명령을 사용합니다.

```powershell
.\scripts\gcp\deploy.ps1
```

두 App VM에 동일 이미지를 배포하려면 `-ScaleOut`을 추가합니다.

```powershell
.\scripts\gcp\deploy.ps1 -ScaleOut
```

파티션과 인스턴스별 listener concurrency도 배포 인자로 고정할 수 있습니다.

```powershell
.\scripts\gcp\deploy.ps1 -ScaleOut -KafkaTopicPartitions 8 -KafkaListenerConcurrency 4
```

스크립트는 Terraform 적용, DB·Grafana·Gateway Secret Manager 확인, Cloud Build, 역할별 Compose 전송과 health check를 수행합니다. 부하 중 verifier는 기본 비활성화하며 정합성 API가 필요할 때만 `-EnableVerifier`를 사용합니다. `TOSS_SECRET_KEY`만 실제 credential이 아닌 WireMock 인증용 고정 테스트 값입니다.

## Test overrides

다음 파일은 운영 배포 구성이 아니라 장애 재현 시 기본 `docker-compose.app.yml` 위에 합성하는 테스트 전용 override입니다.

| 파일 | 용도 |
| --- | --- |
| `docker-compose.scaleout-8p8c.yml` | 2대에서 전체 8 partitions·8 consumers 구성 |
| `docker-compose.failover-test.yml` | Kafka failover 중 unrelated scheduler 비활성화 |
| `docker-compose.scheduler-test.yml` | 두 인스턴스 reconciliation 동시 실행 재현 |

## CI/CD

Pull request CI는 Java 21 테스트, 두 애플리케이션 이미지, Compose·shell·Terraform 구성을 검증합니다. `main` 배포는 Workload Identity Federation으로 인증하고 immutable commit SHA 이미지를 두 App VM에 배포합니다. WIF provider는 지정 저장소의 `main` 브랜치만 허용합니다.

CD에서는 k6를 실행하지 않습니다. 배포 이미지 SHA와 측정 시간을 고정한 뒤 별도 테스트로 실행합니다.

## Load test

다음 예시는 300 RPS·60초·PG timeout 20% 조건입니다.

```powershell
gcloud compute ssh omc-payment-test-vm `
  --project omc-payment `
  --zone asia-northeast3-a `
  --tunnel-through-iap `
  --ssh-flag=-P `
  --ssh-flag=22 `
  --command "sudo RATE=300 DURATION=60s TIMEOUT_WEIGHT=20 /opt/omc-payment/scripts/run-load-test.sh"
```

## Access and cost control

Payment와 Grafana는 IAP 터널로 접근합니다.

```powershell
gcloud compute ssh omc-payment-app-vm --project omc-payment --zone asia-northeast3-a --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 8085:localhost:8085
gcloud compute ssh omc-payment-test-vm --project omc-payment --zone asia-northeast3-a --tunnel-through-iap --ssh-flag=-P --ssh-flag=22 -- -N -L 13000:localhost:13000
```

측정하지 않을 때는 VM을 중지합니다.

```powershell
.\scripts\gcp\stop.ps1
.\scripts\gcp\start.ps1
```

`start.ps1`은 단일 App 구성으로 시작합니다. 스케일아웃 테스트에서는 두 번째 App VM도 시작하도록 옵션을 추가합니다.

```powershell
.\scripts\gcp\start.ps1 -ScaleOut
```

Terraform 관리 리소스를 완전히 제거하려면 프로젝트와 운영자 계정을 명시한 뒤 `terraform -chdir=infra/gcp destroy`를 실행합니다.
