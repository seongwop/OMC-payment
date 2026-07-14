package com.omc.payment.infrastructure.config;

public final class KafkaTopics {

    // 구독 토픽
    public static final String ORDER_CREATED = "order.created";
    public static final String REFUND_REQUESTED = "refund.requested";
    public static final String STOCK_FAILED = "stock.failed";

    // 소비 실패 DLT
    public static final String ORDER_CREATED_DLT = "order.created.DLT";
    public static final String REFUND_REQUESTED_DLT = "refund.requested.DLT";
    public static final String STOCK_FAILED_DLT = "stock.failed.DLT";

    // 발행 토픽
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String REFUND_DONE = "refund.done";

    private KafkaTopics() {
    }
}
