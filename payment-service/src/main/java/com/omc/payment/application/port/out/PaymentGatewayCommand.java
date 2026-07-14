package com.omc.payment.application.port.out;

public final class PaymentGatewayCommand {

    // 객체 생성 방어
    private PaymentGatewayCommand() {}

    public record Confirm(
            String providerPaymentId, // 결제 식별자
            String orderId,
            Long amount,
            String idempotencyKey
    ) {}

    public record RegisterBillingKey(
            String customerKey, // 빌링키 식별용
            String authKey // 빌링키 발급용 승인키
    ) {}

    public record ConfirmBilling(
            String billingKeyId,
            String customerKey,
            String orderId,
            String orderName,
            Long amount,
            String idempotencyKey
    ) {}

    public record GetPayment(
            String providerPaymentID
    ) {}

    public record Cancel(
            String providerPaymentId,
            String cancelReason,
            Long amount,
            String idempotencyKey
    ) {}
}
