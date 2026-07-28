package com.omc.paymenttools.driver.dto;

import com.omc.paymenttools.event.RefundRequestedEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RefundRequestedRequest(
        String eventId,
        @NotNull(message = "주문 ID는 필수입니다.") UUID orderId,
        @NotNull(message = "사용자 ID는 필수입니다.") UUID userId,
        @NotBlank(message = "환불 사유는 필수입니다.") String reason
) {

    // 결제 서비스의 환불 요청 이벤트 계약으로 변환
    public RefundRequestedEvent toEvent(String resolvedEventId) {
        return new RefundRequestedEvent(resolvedEventId, orderId, userId, reason);
    }
}
