package com.omc.paymenttools.driver.dto;

import com.omc.paymenttools.event.OrderCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCreatedRequestTest {

    @Test
    @DisplayName("선택적으로 받은 PG 결제 식별자를 주문 생성 이벤트에 전달한다")
    void toEvent_passesProviderPaymentId() {
        UUID orderId = UUID.randomUUID();
        String providerPaymentId = "mock-approved-but-timeout-" + orderId;
        OrderCreatedRequest request = new OrderCreatedRequest(
                null,
                orderId,
                UUID.randomUUID(),
                "DROP",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                10000L,
                0L,
                10000L,
                null,
                null,
                providerPaymentId
        );

        OrderCreatedEvent event = request.toEvent("event-1");

        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.providerPaymentId()).isEqualTo(providerPaymentId);
    }
}
