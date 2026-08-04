# timeout 혼합 API 부하 단계 측정

## 테스트 목적

PG 승인 timeout이 정상 요청과 후속 복구에 미치는 영향을 확인하고, timeout 20% 조건의 안정 구간을 찾는다.

## 테스트 조건

- payment-service 1대, 2 vCPU·8 GiB
- 정상 승인 80%, 승인 완료 후 응답 지연 20%
- WireMock 응답 지연 5초, payment-service read timeout 3초
- k6 `constant-arrival-rate`, 본 측정 60초

## 측정 결과

| 목표 입력 | 실제 실행 | 정상 승인 p95 | timeout→UNKNOWN | Bulkhead 거절 | 최종 미확정 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 RPS | 5,993건 | 102.25ms | 1,238건 | 0건 | 0건 |
| 150 RPS | 9,001건 | 91.83ms | 1,760건 | 0건 | 0건 |
| 200 RPS | 11,913건 | 975.18ms | 2,386건 | 38건 | 0건 |

## 결과 해석

100·150 RPS에서는 timeout 요청이 약 3초간 스레드를 점유했지만 Bulkhead 한도에 도달하지 않았다. 정상 요청 p95는 102.25ms 이하였고, 150 RPS의 app CPU 평균은 77.96%, Tomcat busy 최대는 114/200이었다.

200 RPS에서는 dropped 88건, Bulkhead 거절 38건이 발생하고 정상 요청 p95가 975.18ms까지 상승했다. timeout 20% 조건에서는 200 RPS부터 포화 구간으로 봤다. 거절된 38건은 PG 호출 전에 `READY`로 돌아갔고, 테스트 클라이언트가 재시도하지 않아 5분 TTL 뒤 `FAILED`가 됐다. PG 승인 결과를 잃어버린 건은 아니다.

## 다음 단계

- 안정 구간인 150 RPS를 기준으로 UNKNOWN 복구 시간 측정
- Bulkhead 거절과 PG 응답 불명확 건을 분리해 최종 상태 대조
- 입력 종료 후 UNKNOWN과 Outbox가 모두 비워질 때까지 관측
