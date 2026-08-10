# 후속 복구와 최종 정합성

## 테스트 목적

승인 후 응답이 유실된 결제가 `CONFIRM_UNKNOWN`에서 실제 PG 상태를 재조회해 수렴하는지, 입력 종료 뒤 Outbox까지 완전히 배출되는지 추적한다.

## 테스트 조건

- 승인 timeout 20%, 본 측정 60초
- UNKNOWN recovery batch 100건, fixed delay 30초
- PG 조회 결과는 모두 `DONE`인 approved-but-timeout 시나리오

상태 수렴 경로:

```text
PG 승인 완료 → HTTP timeout → CONFIRM_UNKNOWN
             → recovery scheduler PG 조회
             → DONE 확인 → PAID → Outbox PUBLISHED
```

## 측정 결과

| 조건 | 생성된 UNKNOWN | 최종 `PAID` 보정 | 복구 처리율 | 입력 종료 후 완전 수렴 | 미확정·중복 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 100 RPS·timeout 20% | 1,238건 | 1,238건 | 약 3.26건/s | 349.248초 | 0건 |
| 150 RPS·timeout 20% | 1,760건 | 1,760건 | 약 3.13건/s | 501.337초 | 0건 |
| 200 RPS·timeout 20% | 2,386건 | 2,386건 | 약 3.26건/s | 673.599초 | 0건 |

## 결과 해석

입력률이 증가해도 UNKNOWN 복구 속도는 약 3.2건/s로 비슷했다. 200 RPS의 최종 `FAILED` 38건은 Bulkhead가 PG 호출 전에 거절한 요청이다. 생성된 `CONFIRM_UNKNOWN` 2,386건은 전량 `PAID`로 보정됐다.

Outbox는 최종적으로 `INIT·PUBLISHING·FAILED·DEAD=0`에 도달했다. 다만 발행 처리량이 약 17 events/s로 고정돼 전체 수렴 시간은 입력 건수에 따라 길어졌다.

## 다음 단계

- UNKNOWN 대상의 원자적 claim과 batch·주기 조정
- 취소 timeout, PG 조회 실패와 `RECOVERY_FAILED` 분기 별도 검증
- Outbox publisher 개선 전후 전체 수렴 시간 비교
