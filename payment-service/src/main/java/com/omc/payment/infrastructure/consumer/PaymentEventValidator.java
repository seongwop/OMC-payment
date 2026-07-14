package com.omc.payment.infrastructure.consumer;

import com.omc.payment.application.event.dto.inbound.OrderCreatedEvent;
import com.omc.payment.application.event.dto.inbound.RefundRequestedEvent;
import com.omc.payment.application.event.dto.inbound.StockFailedEvent;
import com.omc.payment.domain.exception.NonRetryablePaymentException;
import com.omc.payment.domain.exception.PaymentErrorCode;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventValidator {

    public void validate(OrderCreatedEvent event) {
        requireNonNull(event, "주문 생성 이벤트는 필수입니다");
        requireNotBlank(event.eventId(), "이벤트 ID는 필수입니다");
        requireNonNull(event.orderId(), "주문 ID는 필수입니다");
        requireNonNull(event.userId(), "사용자 ID는 필수입니다");
        requireNotBlank(event.orderType(), "주문 유형은 필수입니다");

        if ("DROP".equalsIgnoreCase(event.orderType())) {
            requireNonNull(event.dropId(), "드롭 ID는 필수입니다");
            return;
        }

        if ("RAFFLE".equalsIgnoreCase(event.orderType())) {
            requireNonNull(event.raffleId(), "래플 ID는 필수입니다");
            return;
        }

        throw new NonRetryablePaymentException(
                PaymentErrorCode.PAYMENT_FAILED,
                "지원하지 않는 주문 유형입니다: " + event.orderType()
        );
    }

    public void validate(RefundRequestedEvent event) {
        requireNonNull(event, "환불 요청 이벤트는 필수입니다");
        requireNotBlank(event.eventId(), "이벤트 ID는 필수입니다");
        requireNonNull(event.orderId(), "주문 ID는 필수입니다");
        requireNonNull(event.userId(), "사용자 ID는 필수입니다");
        requireNotBlank(event.reason(), "환불 사유는 필수입니다");
    }

    public void validate(StockFailedEvent event) {
        requireNonNull(event, "재고 차감 실패 이벤트는 필수입니다");
        requireNotBlank(event.eventId(), "이벤트 ID는 필수입니다");
        requireNonNull(event.orderId(), "주문 ID는 필수입니다");
    }

    private void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new NonRetryablePaymentException(PaymentErrorCode.PAYMENT_FAILED, message);
        }
    }

    private void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new NonRetryablePaymentException(PaymentErrorCode.PAYMENT_FAILED, message);
        }
    }
}
