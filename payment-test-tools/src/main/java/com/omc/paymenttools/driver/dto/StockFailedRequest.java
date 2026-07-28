package com.omc.paymenttools.driver.dto;

import com.omc.paymenttools.event.StockFailedEvent;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StockFailedRequest(
        String eventId,
        @NotNull(message = "주문 ID는 필수입니다.") UUID orderId
) {

    // 결제 서비스의 재고 차감 실패 이벤트 계약으로 변환
    public StockFailedEvent toEvent(String resolvedEventId) {
        return new StockFailedEvent(resolvedEventId, orderId);
    }
}
