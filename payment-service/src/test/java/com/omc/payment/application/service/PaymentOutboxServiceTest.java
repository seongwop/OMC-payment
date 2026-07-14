package com.omc.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.OutboxAggregateType;
import com.omc.payment.domain.enums.PaymentMethod;
import com.omc.payment.domain.enums.Provider;
import com.omc.payment.domain.enums.SalesType;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import com.omc.payment.infrastructure.config.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 아웃박스 서비스 테스트")
class PaymentOutboxServiceTest {

    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DROP_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID COUPON_ID = UUID.randomUUID();

    @Mock private PaymentOutboxEventRepository paymentOutboxEventRepository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private PaymentOutboxService paymentOutboxService;

    @Nested
    @DisplayName("아웃박스 적재")
    class Save {

        @Test
        @DisplayName("결제 완료 이벤트 본문을 저장한다")
        void savePaymentCompleted() throws IOException {
            ObjectMapper realObjectMapper = new ObjectMapper();
            PaymentOutboxService service = new PaymentOutboxService(paymentOutboxEventRepository, realObjectMapper);
            Payment payment = createApprovedPayment();

            service.savePaymentCompleted(payment);

            ArgumentCaptor<PaymentOutboxEvent> outboxCaptor = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
            verify(paymentOutboxEventRepository).save(outboxCaptor.capture());

            PaymentOutboxEvent outboxEvent = outboxCaptor.getValue();
            JsonNode payload = realObjectMapper.readTree(outboxEvent.getPayload());

            assertThat(outboxEvent.getAggregateType()).isEqualTo(OutboxAggregateType.PAYMENT);
            assertThat(outboxEvent.getAggregateId()).isEqualTo(PAYMENT_ID);
            assertThat(outboxEvent.getEventType()).isEqualTo(KafkaTopics.PAYMENT_COMPLETED);
            assertThat(payload.get("paymentId").asText()).isEqualTo(PAYMENT_ID.toString());
            assertThat(payload.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
            assertThat(payload.get("userId").asText()).isEqualTo(USER_ID.toString());
            assertThat(payload.get("finalAmount").asLong()).isEqualTo(9000L);
        }

        @Test
        @DisplayName("결제 실패 이벤트는 실패 메시지를 우선 저장한다")
        void savePaymentFailed() throws IOException {
            ObjectMapper realObjectMapper = new ObjectMapper();
            PaymentOutboxService service = new PaymentOutboxService(paymentOutboxEventRepository, realObjectMapper);
            Payment payment = createFailedPayment();

            service.savePaymentFailed(payment);

            ArgumentCaptor<PaymentOutboxEvent> outboxCaptor = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
            verify(paymentOutboxEventRepository).save(outboxCaptor.capture());

            PaymentOutboxEvent outboxEvent = outboxCaptor.getValue();
            JsonNode payload = realObjectMapper.readTree(outboxEvent.getPayload());

            assertThat(outboxEvent.getEventType()).isEqualTo(KafkaTopics.PAYMENT_FAILED);
            assertThat(payload.get("failureReason").asText()).isEqualTo("카드 승인이 거절되었습니다");
        }

        @Test
        @DisplayName("환불 완료 이벤트 본문을 저장한다")
        void saveRefundDone() throws IOException {
            ObjectMapper realObjectMapper = new ObjectMapper();
            PaymentOutboxService service = new PaymentOutboxService(paymentOutboxEventRepository, realObjectMapper);
            Payment payment = createCanceledPayment();

            service.saveRefundDone(payment);

            ArgumentCaptor<PaymentOutboxEvent> outboxCaptor = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
            verify(paymentOutboxEventRepository).save(outboxCaptor.capture());

            PaymentOutboxEvent outboxEvent = outboxCaptor.getValue();
            JsonNode payload = realObjectMapper.readTree(outboxEvent.getPayload());

            assertThat(outboxEvent.getEventType()).isEqualTo(KafkaTopics.REFUND_DONE);
            assertThat(payload.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
            assertThat(payload.get("amount").asLong()).isEqualTo(9000L);
            assertThat(payload.get("refundReason").asText()).isEqualTo("환불 완료");
        }

        @Test
        @DisplayName("이벤트 본문 직렬화에 실패하면 예외가 발생한다")
        void savePaymentCompleted_serializationFailure() throws Exception {
            Payment payment = createApprovedPayment();
            when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("직렬화 실패") {});

            assertThatThrownBy(() -> paymentOutboxService.savePaymentCompleted(payment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("결제 아웃박스 이벤트");

            verify(paymentOutboxEventRepository, never()).save(any(PaymentOutboxEvent.class));
        }
    }

    private Payment createApprovedPayment() {
        Payment payment = createBasePayment();
        payment.startConfirming();
        payment.approve("결제 승인 아이디");
        return payment;
    }

    private Payment createFailedPayment() {
        Payment payment = createBasePayment();
        payment.startConfirming();
        payment.fail("TOSS-400", "카드 승인이 거절되었습니다");
        return payment;
    }

    private Payment createCanceledPayment() {
        Payment payment = createApprovedPayment();
        payment.cancel("취소 아이디", CancellationCode.USER_CANCEL, "환불 완료");
        return payment;
    }

    private Payment createBasePayment() {
        Payment payment = Payment.create(
                ORDER_ID,
                DROP_ID,
                null,
                null,
                PRODUCT_ID,
                COUPON_ID,
                USER_ID,
                SalesType.DROP,
                10000L,
                1000L,
                9000L,
                Provider.TOSS,
                "결제 승인 아이디",
                PaymentMethod.CARD
        );
        ReflectionTestUtils.setField(payment, "paymentId", PAYMENT_ID);
        return payment;
    }
}
