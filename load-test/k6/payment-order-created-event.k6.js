import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const options = {
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        order_created_events: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 100),
            timeUnit: '1s',
            duration: __ENV.DURATION || '60s',
            preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 100),
            maxVUs: Number(__ENV.MAX_VUS || 200),
            gracefulStop: __ENV.GRACEFUL_STOP || '30s',
        },
    },
    thresholds: {
        event_driver_publish_accepted: ['rate>0.999'],
        event_driver_publish_duration: ['p(95)<500'],
    },
};

const EVENT_DRIVER_BASE_URL =
    __ENV.EVENT_DRIVER_BASE_URL || 'http://payment-test-tools:8090';

const DROP_ID = __ENV.DROP_ID || '019efc9f-0abb-762e-a010-a335be0e6006';
const PRODUCT_ID = __ENV.PRODUCT_ID || '019efc9f-0abb-762e-a010-a335be0e6007';

const published = new Counter('event_driver_publish_success');
const publishFailed = new Counter('event_driver_publish_failed');
const publishAccepted = new Rate('event_driver_publish_accepted');
const publishDuration = new Trend('event_driver_publish_duration', true);

export default function () {
    const eventId = uuidv4();
    const orderId = uuidv4();
    const userId = uuidv4();

    const payload = JSON.stringify({
        eventId,
        orderId,
        userId,
        orderType: 'DROP',
        dropId: DROP_ID,
        productId: PRODUCT_ID,
        raffleId: null,
        entryId: null,
        originalAmount: 10000,
        discountAmount: 0,
        finalAmount: 10000,
        couponId: null,
        billingKeyId: null,
        providerPaymentId: `mock-success-${orderId}`,
    });

    const response = http.post(
        `${EVENT_DRIVER_BASE_URL}/internal/test/events/order-created`,
        payload,
        {
            headers: { 'Content-Type': 'application/json' },
            tags: { event_topic: 'order.created' },
            timeout: __ENV.REQUEST_TIMEOUT || '10s',
        },
    );

    const accepted = check(response, {
        'event driver accepted': (res) => res.status === 202,
    });

    publishAccepted.add(accepted);
    if (accepted) {
        published.add(1);
        publishDuration.add(response.timings.duration);
    } else {
        publishFailed.add(1);
    }
}

function uuidv4() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
        const random = Math.floor(Math.random() * 16);
        const value = char === 'x' ? random : (random & 0x3) | 0x8;
        return value.toString(16);
    });
}
