import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// k6 부하 테스트 설정
export const options = {
    scenarios: {
        // 시나리오: Toss WireMock 장애 응답을 섞은 결제 승인 부하 테스트
        confirm_load: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 100),
            timeUnit: __ENV.TIME_UNIT || '1s',
            duration: __ENV.DURATION || '90s',
            preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || __ENV.VUS || 80),
            maxVUs: Number(__ENV.MAX_VUS || 200),
        },
    },
    thresholds: {
        // 성공 기준 설정
        'http_req_duration{payment_scenario:success}': ['p(95)<1000'], // 정상 승인 요청의 95%가 1초 이내에 완료되어야 함
        'http_req_duration{payment_scenario:card_limit}': ['p(95)<1000'], // 명확한 PG 실패 요청의 95%가 1초 이내에 완료되어야 함
        payment_confirm_accepted: ['rate>0.70'], // 70% 이상은 서비스가 정책적으로 처리한 응답이어야 함
    },
};

// 테스트 환경 설정
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085'; // payment-service 직접 호출 URL
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '60s'; // recovery 검증에서는 k6가 먼저 끊지 않도록 넉넉하게 설정
const THINK_TIME_MS = Number(__ENV.THINK_TIME_MS || 100); // 요청 사이 대기 시간

// 결제 요청에 사용할 기본 참조값
const DROP_ID = __ENV.DROP_ID || '019efc9f-0abb-762e-a010-a335be0e6006'; // 테스트용 dropId
const PRODUCT_ID = __ENV.PRODUCT_ID || '019efc9f-0abb-762e-a010-a335be0e6007'; // 테스트용 productId
const ORIGINAL_AMOUNT = Number(__ENV.ORIGINAL_AMOUNT || 10000); // 원 결제 금액
const DISCOUNT_AMOUNT = Number(__ENV.DISCOUNT_AMOUNT || 0); // 쿠폰 없음
const FINAL_AMOUNT = Number(__ENV.FINAL_AMOUNT || 10000); // 최종 결제 금액

// 테스트 결과 확인용 커스텀 지표
const confirmSuccess = new Counter('payment_confirm_success'); // 성공 승인 시나리오 수
const confirmBusinessFailure = new Counter('payment_confirm_business_failure'); // 명확한 PG 실패 시나리오 수
const confirmUnknownCandidate = new Counter('payment_confirm_unknown_candidate'); // UNKNOWN 후속 처리 후보 수
const confirmUnexpected = new Counter('payment_confirm_unexpected'); // 예상 밖 응답 수
const confirmAccepted = new Rate('payment_confirm_accepted'); // 정책적으로 처리된 응답 비율

export default function () {
    // 1. 매 요청마다 WireMock 응답 시나리오를 선택
    const scenario = choosePaymentScenario();

    // 2. 매 요청마다 새로운 주문/유저 ID 생성
    const orderId = uuidv4();
    const userId = uuidv4();

    // 3. providerPaymentId prefix로 WireMock 매핑 선택
    //    mock-success-*                    -> 승인 성공
    //    mock-card-limit-*                 -> 카드 한도 초과
    //    mock-approved-but-timeout-*       -> confirm timeout 후 조회 DONE
    //    mock-failed-after-timeout-*       -> confirm timeout 후 조회 ABORTED
    //    mock-pending-long-timeout-*       -> confirm timeout 후 조회 IN_PROGRESS
    //    mock-not-approved-timeout-*       -> confirm timeout 후 조회 404
    //    mock-lookup-timeout-*             -> confirm timeout 후 조회 timeout
    //    mock-lookup-rate-limit-timeout-*  -> confirm timeout 후 조회 429
    //    mock-lookup-server-error-timeout-* -> confirm timeout 후 조회 500
    //    mock-canceled-after-timeout-*     -> confirm timeout 후 조회 CANCELED
    //    mock-network-error-*              -> confirm connection reset
    const providerPaymentId = `${scenario.paymentKeyPrefix}-${orderId}`;

    // 4. 결제 승인 요청 Body 생성
    const payload = JSON.stringify({
        orderID: orderId,
        dropId: DROP_ID,
        productId: PRODUCT_ID,
        providerPaymentId,
        couponID: null,
        originalAmount: ORIGINAL_AMOUNT,
        discountAmount: DISCOUNT_AMOUNT,
        finalAmount: FINAL_AMOUNT,
    });

    // 5. 결제 승인 API 호출
    const res = http.post(`${BASE_URL}/internal/v1/payments/confirm`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'X-User-Id': userId, // Controller의 @RequestHeader("X-User-Id") 처리용
        },
        tags: {
            payment_scenario: scenario.name, // k6 결과에서 시나리오별 응답 구분용
        },
        timeout: REQUEST_TIMEOUT,
    });

    // 6. 검증
    //    201: 정상 처리 또는 실패/UNKNOWN 상태 저장 후 응답
    //    400/409/502/503: 서비스가 정책적으로 반환할 수 있는 처리된 응답
    //    500: 내부 예외가 그대로 터진 것이므로 실패로 봄
    const accepted = check(res, {
        'is handled response': (r) => [201, 400, 409, 502, 503].includes(r.status),
        'is not internal server error': (r) => r.status !== 500,
    });

    // 7. 시나리오별 메트릭 기록
    confirmAccepted.add(accepted);
    recordScenarioMetric(scenario.name, res.status);

    // 8. 예상 밖 응답은 원인 확인을 위해 body 일부 출력
    if (!accepted) {
        console.error(`unexpected response scenario=${scenario.name} status=${res.status} body=${safeBody(res)}`);
    }

    // 9. 유저의 행동 패턴 모사
    sleep(THINK_TIME_MS / 1000);
}

function choosePaymentScenario() {
    const roll = Math.random() * 100;

    // recovery 검증용 비율이다.
    // timeout 계열은 의도적으로 UNKNOWN을 만들지만, k6 요청 timeout으로 실패하지 않도록 REQUEST_TIMEOUT을 넉넉하게 둔다.
    const scenarios = [
        { name: 'success', paymentKeyPrefix: 'mock-success', weight: Number(__ENV.SUCCESS_WEIGHT || 40) },
        { name: 'card_limit', paymentKeyPrefix: 'mock-card-limit', weight: Number(__ENV.CARD_LIMIT_WEIGHT || 10) },
        { name: 'approved_but_timeout', paymentKeyPrefix: 'mock-approved-but-timeout', weight: Number(__ENV.APPROVED_TIMEOUT_WEIGHT || __ENV.TIMEOUT_WEIGHT || 10) },
        { name: 'failed_after_timeout', paymentKeyPrefix: 'mock-failed-after-timeout', weight: Number(__ENV.FAILED_AFTER_TIMEOUT_WEIGHT || 10) },
        { name: 'pending_long_timeout', paymentKeyPrefix: 'mock-pending-long-timeout', weight: Number(__ENV.PENDING_LONG_TIMEOUT_WEIGHT || 10) },
        { name: 'not_approved_timeout', paymentKeyPrefix: 'mock-not-approved-timeout', weight: Number(__ENV.NOT_APPROVED_TIMEOUT_WEIGHT || 5) },
        { name: 'lookup_timeout', paymentKeyPrefix: 'mock-lookup-timeout', weight: Number(__ENV.LOOKUP_TIMEOUT_WEIGHT || 5) },
        { name: 'lookup_rate_limit', paymentKeyPrefix: 'mock-lookup-rate-limit-timeout', weight: Number(__ENV.LOOKUP_RATE_LIMIT_WEIGHT || 5) },
        { name: 'lookup_server_error', paymentKeyPrefix: 'mock-lookup-server-error-timeout', weight: Number(__ENV.LOOKUP_SERVER_ERROR_WEIGHT || 3) },
        { name: 'canceled_after_timeout', paymentKeyPrefix: 'mock-canceled-after-timeout', weight: Number(__ENV.CANCELED_AFTER_TIMEOUT_WEIGHT || 2) },
        { name: 'network_error', paymentKeyPrefix: 'mock-network-error', weight: Number(__ENV.NETWORK_ERROR_WEIGHT || 0) },
    ];

    let cumulativeWeight = 0;
    for (const scenario of scenarios) {
        cumulativeWeight += scenario.weight;
        if (roll < cumulativeWeight) {
            return scenario;
        }
    }

    // 가중치 합이 100보다 작으면 남은 비율은 가장 일반적인 승인 후 timeout 케이스로 처리
    return { name: 'approved_but_timeout', paymentKeyPrefix: 'mock-approved-but-timeout' };
}

function recordScenarioMetric(name, status) {
    // 성공 시나리오는 DB에서 PAID와 payment.completed Outbox로 최종 확인
    if (status === 201 && name === 'success') {
        confirmSuccess.add(1);
        return;
    }

    // 명확한 PG 실패는 DB에서 FAILED와 payment.failed Outbox로 최종 확인
    if (name === 'card_limit') {
        confirmBusinessFailure.add(1);
        return;
    }

    // timeout/reset/lookup 장애 계열은 DB에서 UNKNOWN 후속 처리 수렴 여부 확인
    if (
        name === 'network_error' ||
        name === 'approved_but_timeout' ||
        name === 'failed_after_timeout' ||
        name === 'pending_long_timeout' ||
        name === 'not_approved_timeout' ||
        name === 'lookup_timeout' ||
        name === 'lookup_rate_limit' ||
        name === 'lookup_server_error' ||
        name === 'canceled_after_timeout'
    ) {
        confirmUnknownCandidate.add(1);
        return;
    }

    confirmUnexpected.add(1);
}

function safeBody(res) {
    if (!res || !res.body) {
        return '';
    }
    return res.body.length > 500 ? `${res.body.substring(0, 500)}...` : res.body;
}

function uuidv4() {
    // 외부 CDN import 없이 테스트용 UUID 형태 문자열 생성
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
        const random = Math.floor(Math.random() * 16);
        const value = char === 'x' ? random : (random & 0x3) | 0x8;
        return value.toString(16);
    });
}
