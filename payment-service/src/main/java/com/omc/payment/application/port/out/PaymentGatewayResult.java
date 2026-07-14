package com.omc.payment.application.port.out;

import com.omc.payment.domain.enums.PaymentGatewayStatus;

public final class PaymentGatewayResult {

    // 객체 생성 방어
    private PaymentGatewayResult() {}

    // 일반 결제, 빌링키 자동결제 반환
    public record Confirm(
            String providerPaymentId
    ) {}

    public record RegisterBillingKey(
            String billingKeyID
    ) {}

    public record Payment(
            String providerPaymentId,
            String orderId,
            PaymentGatewayStatus status,
            Long totalAmount,
            Long cancelableAmount, // 취소 가능 금액
            String providerTransactionId // 결제 한 건에 해당하는 마지막 트랜잭션 식별자
    ) {}

    public record Cancel(
            String providerCancellationId
    ) {}
}
