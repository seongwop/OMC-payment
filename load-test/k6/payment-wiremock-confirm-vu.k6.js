import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// 포트 고갈 재현용 VU 기반 부하 테스트 설정
export const options = {
    scenarios: {
        // VU를 점진적으로 올려 연결 계층과 서버 포화 한계가 나타나는 구간을 확인한다.
        // constant-arrival-rate와 달리 초당 요청 수를 제한하지 않기 때문에
        // 로컬 부하 발생기와 서버가 감당 가능한 자연 처리량 한계를 확인하기 좋다.
        confirm_vu_port_exhaustion: {
            executor: 'ramping-vus',
            stages: [
                { duration: __ENV.RAMP_UP || '30s', target: Number(__ENV.VUS || 200) },
                { duration: __ENV.DURATION || '30s', target: Number(__ENV.VUS || 200) },
                { duration: __ENV.RAMP_DOWN || '30s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        'http_req_duration{payment_scenario:success}': ['p(95)<1000'],
        payment_confirm_accepted: ['rate>0.70'],
    },
};

// 테스트 환경 설정
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '60s';
const THINK_TIME_MS = Number(__ENV.THINK_TIME_MS || 0);

// 결제 요청에 사용할 기본 참조값
const DROP_ID = __ENV.DROP_ID || '019efc9f-0abb-762e-a010-a335be0e6006';
const PRODUCT_ID = __ENV.PRODUCT_ID || '019efc9f-0abb-762e-a010-a335be0e6007';
const ORIGINAL_AMOUNT = Number(__ENV.ORIGINAL_AMOUNT || 10000);
const DISCOUNT_AMOUNT = Number(__ENV.DISCOUNT_AMOUNT || 0);
const FINAL_AMOUNT = Number(__ENV.FINAL_AMOUNT || 10000);

// 테스트 결과 확인용 커스텀 지표
const confirmSuccess = new Counter('payment_confirm_success');
const confirmUnknownCandidate = new Counter('payment_confirm_unknown_candidate');
const confirmUnexpected = new Counter('payment_confirm_unexpected');
const confirmAccepted = new Rate('payment_confirm_accepted');

export default function () {
    // 1. 매 요청마다 WireMock 응답 시나리오를 선택한다.
    const scenario = choosePaymentScenario();

    // 2. 매 요청마다 새로운 주문/사용자 ID를 생성한다.
    const orderId = uuidv4();
    const userId = uuidv4();

    // 3. providerPaymentId prefix로 WireMock 매핑을 선택한다.
    const providerPaymentId = `${scenario.paymentKeyPrefix}-${orderId}`;

    // 4. 결제 승인 요청 Body를 생성한다.
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

    // 5. 결제 승인 API를 직접 호출한다.
    const res = http.post(`${BASE_URL}/internal/v1/payments/confirm`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'X-User-Id': userId,
        },
        tags: {
            payment_scenario: scenario.name,
        },
        timeout: REQUEST_TIMEOUT,
    });

    // 6. payment-service가 제어한 응답인지 확인한다.
    const accepted = check(res, {
        'is handled response': (r) => [201, 400, 409, 502, 503].includes(r.status),
        'is not internal server error': (r) => r.status !== 500,
    });

    // 7. 시나리오별 커스텀 지표를 기록한다.
    confirmAccepted.add(accepted);
    recordScenarioMetric(scenario.name, res.status);

    // 8. 예상 밖 응답은 원인 확인을 위해 일부 body를 출력한다.
    if (!accepted) {
        console.error(`unexpected response scenario=${scenario.name} status=${res.status} body=${safeBody(res)}`);
    }

    // 9. 포트 고갈 재현 시에는 기본값 0ms로 최대한 빠르게 반복한다.
    sleep(THINK_TIME_MS / 1000);
}

function choosePaymentScenario() {
    const roll = Math.random() * 100;
    const scenarios = [
        { name: 'success', paymentKeyPrefix: 'mock-success', weight: Number(__ENV.SUCCESS_WEIGHT || 100) },
        { name: 'approved_but_timeout', paymentKeyPrefix: 'mock-approved-but-timeout', weight: Number(__ENV.APPROVED_TIMEOUT_WEIGHT || 0) },
        { name: 'network_error', paymentKeyPrefix: 'mock-network-error', weight: Number(__ENV.NETWORK_ERROR_WEIGHT || 0) },
    ];

    let cumulativeWeight = 0;
    for (const scenario of scenarios) {
        cumulativeWeight += scenario.weight;
        if (roll < cumulativeWeight) {
            return scenario;
        }
    }

    return { name: 'success', paymentKeyPrefix: 'mock-success' };
}

function recordScenarioMetric(name, status) {
    if (status === 201 && name === 'success') {
        confirmSuccess.add(1);
        return;
    }

    if (name === 'approved_but_timeout' || name === 'network_error') {
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
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
        const random = Math.floor(Math.random() * 16);
        const value = char === 'x' ? random : (random & 0x3) | 0x8;
        return value.toString(16);
    });
}
