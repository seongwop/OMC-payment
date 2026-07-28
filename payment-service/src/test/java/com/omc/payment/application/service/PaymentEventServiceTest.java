package com.omc.payment.application.service;

import com.omc.payment.application.event.dto.inbound.OrderCreatedEvent;
import com.omc.payment.application.event.dto.inbound.RefundRequestedEvent;
import com.omc.payment.application.event.dto.inbound.StockFailedEvent;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.exception.RetryablePaymentException;
import com.omc.payment.infrastructure.config.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 이벤트 서비스 테스트")
class PaymentEventServiceTest {

    private static final String EVENT_ID = "이벤트 아이디";
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DROP_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID COUPON_ID = UUID.randomUUID();
    private static final UUID RAFFLE_ID = UUID.randomUUID();
    private static final UUID ENTRY_ID = UUID.randomUUID();

    @Mock private PaymentInboxService paymentInboxService;
    @Mock private PaymentCoreService paymentCoreService;
    @Mock private PaymentIdempotencyService paymentIdempotencyService;

    @InjectMocks
    private PaymentEventService paymentEventService;

    @BeforeEach
    void setUp() {
        lenient().when(paymentIdempotencyService.confirmKey(any(UUID.class)))
                .thenAnswer(invocation -> "payment:confirm:" + invocation.getArgument(0));
        lenient().when(paymentIdempotencyService.cancelKey(any(UUID.class)))
                .thenAnswer(invocation -> "payment:cancel:" + invocation.getArgument(0));
        lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(paymentIdempotencyService).execute(anyString(), any(Runnable.class));
    }

    @Nested
    @DisplayName("주문 생성 이벤트 처리")
    class HandleOrderCreated {

        @Test
        @DisplayName("중복 이벤트면 결제를 다시 처리하지 않는다")
        void handleOrderCreated_duplicate() {
            OrderCreatedEvent event = dropOrderCreatedEvent();
            when(paymentInboxService.isAlreadyProcessed(EVENT_ID, KafkaTopics.ORDER_CREATED)).thenReturn(true);

            paymentEventService.handleOrderCreated(event);

            verify(paymentCoreService, never()).confirmPayment(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyString()
            );
            verify(paymentCoreService, never()).confirmBillingPayment(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong()
            );
        }

        @Test
        @DisplayName("래플 주문이면 빌링키 결제로 분기한다")
        void handleOrderCreated_raffle() {
            OrderCreatedEvent event = raffleOrderCreatedEvent();
            when(paymentInboxService.isAlreadyProcessed(EVENT_ID, KafkaTopics.ORDER_CREATED)).thenReturn(false);

            paymentEventService.handleOrderCreated(event);

            verify(paymentCoreService).confirmBillingPayment(
                    ORDER_ID,
                    ENTRY_ID,
                    RAFFLE_ID,
                    PRODUCT_ID,
                    COUPON_ID,
                    USER_ID,
                    "빌링키 아이디",
                    null,
                    30000L,
                    3000L,
                    27000L
            );
        }

        @Test
        @DisplayName("드롭 주문이면 일반 결제로 분기한다")
        void handleOrderCreated_drop() {
            OrderCreatedEvent event = dropOrderCreatedEvent();
            when(paymentInboxService.isAlreadyProcessed(EVENT_ID, KafkaTopics.ORDER_CREATED)).thenReturn(false);

            paymentEventService.handleOrderCreated(event);

            ArgumentCaptor<String> providerPaymentIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(paymentCoreService).confirmPayment(
                    org.mockito.ArgumentMatchers.eq(ORDER_ID),
                    org.mockito.ArgumentMatchers.eq(DROP_ID),
                    org.mockito.ArgumentMatchers.eq(PRODUCT_ID),
                    org.mockito.ArgumentMatchers.eq(COUPON_ID),
                    org.mockito.ArgumentMatchers.eq(USER_ID),
                    org.mockito.ArgumentMatchers.eq(10000L),
                    org.mockito.ArgumentMatchers.eq(1000L),
                    org.mockito.ArgumentMatchers.eq(9000L),
                    providerPaymentIdCaptor.capture()
            );
            assertThat(providerPaymentIdCaptor.getValue()).isEqualTo(ORDER_ID.toString());
        }

        @Test
        @DisplayName("PG 결제 식별자가 있으면 orderId 대신 해당 값을 사용한다")
        void handleOrderCreated_usesProvidedProviderPaymentId() {
            String providerPaymentId = "mock-approved-but-timeout-" + ORDER_ID;
            OrderCreatedEvent event = dropOrderCreatedEvent(providerPaymentId);
            when(paymentInboxService.isAlreadyProcessed(EVENT_ID, KafkaTopics.ORDER_CREATED)).thenReturn(false);

            paymentEventService.handleOrderCreated(event);

            verify(paymentCoreService).confirmPayment(
                    org.mockito.ArgumentMatchers.eq(ORDER_ID),
                    org.mockito.ArgumentMatchers.eq(DROP_ID),
                    org.mockito.ArgumentMatchers.eq(PRODUCT_ID),
                    org.mockito.ArgumentMatchers.eq(COUPON_ID),
                    org.mockito.ArgumentMatchers.eq(USER_ID),
                    org.mockito.ArgumentMatchers.eq(10000L),
                    org.mockito.ArgumentMatchers.eq(1000L),
                    org.mockito.ArgumentMatchers.eq(9000L),
                    org.mockito.ArgumentMatchers.eq(providerPaymentId)
            );
        }

        @Test
        @DisplayName("결제 승인이 재시도 예외로 실패하면 Inbox를 실패 처리하고 예외를 다시 던진다")
        void handleOrderCreated_retryableFailureMarksInboxFailed() {
            OrderCreatedEvent event = dropOrderCreatedEvent();
            when(paymentInboxService.isAlreadyProcessed(EVENT_ID, KafkaTopics.ORDER_CREATED)).thenReturn(false);
            when(paymentCoreService.confirmPayment(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyString()
            )).thenThrow(new RetryablePaymentException(
                    PaymentErrorCode.PAYMENT_GATEWAY_CONNECTION_FAILED,
                    "PG 동시 요청 한도를 초과했습니다."
            ));

            assertThatThrownBy(() -> paymentEventService.handleOrderCreated(event))
                    .isInstanceOf(RetryablePaymentException.class);

            verify(paymentInboxService).markFailed(EVENT_ID);
            verify(paymentInboxService, never()).markProcessed(EVENT_ID);
        }
    }

    @Nested
    @DisplayName("환불 요청 이벤트 처리")
    class HandleRefundRequested {

        @Test
        @DisplayName("중복 환불 이벤트면 취소를 다시 처리하지 않는다")
        void handleRefundRequested_duplicate() {
            RefundRequestedEvent event = new RefundRequestedEvent(EVENT_ID, ORDER_ID, USER_ID, "환불");
            when(paymentInboxService.isAlreadyProcessed(EVENT_ID, KafkaTopics.REFUND_REQUESTED)).thenReturn(true);

            paymentEventService.handleRefundRequested(event);

            verify(paymentCoreService, never()).cancelPaymentByOrderId(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString()
            );
        }

        @Test
        @DisplayName("환불 사유와 함께 주문 기준 취소를 호출한다")
        void handleRefundRequested_success() {
            RefundRequestedEvent event = new RefundRequestedEvent(EVENT_ID, ORDER_ID, USER_ID, "환불 요청");
            when(paymentInboxService.isAlreadyProcessed(EVENT_ID, KafkaTopics.REFUND_REQUESTED)).thenReturn(false);

            paymentEventService.handleRefundRequested(event);

            verify(paymentCoreService).cancelPaymentByOrderId(ORDER_ID, null, "환불 요청");
        }
    }

    @Test
    @DisplayName("재고 차감 실패 이벤트를 취소 흐름으로 연결한다")
    void handleStockFailed_success() {
        StockFailedEvent event = new StockFailedEvent(EVENT_ID, ORDER_ID);
        when(paymentInboxService.isAlreadyProcessed(EVENT_ID, KafkaTopics.STOCK_FAILED)).thenReturn(false);

        paymentEventService.handleStockFailed(event);

        verify(paymentCoreService).cancelPaymentByOrderId(
                ORDER_ID,
                CancellationCode.STOCK_DEDUCT_FAILED,
                "재고 차감 실패"
        );
    }

    private OrderCreatedEvent dropOrderCreatedEvent() {
        return dropOrderCreatedEvent(null);
    }

    private OrderCreatedEvent dropOrderCreatedEvent(String providerPaymentId) {
        return new OrderCreatedEvent(
                EVENT_ID,
                ORDER_ID,
                USER_ID,
                "DROP",
                DROP_ID,
                PRODUCT_ID,
                null,
                null,
                10000L,
                1000L,
                9000L,
                COUPON_ID,
                null,
                providerPaymentId
        );
    }

    private OrderCreatedEvent raffleOrderCreatedEvent() {
        return new OrderCreatedEvent(
                EVENT_ID,
                ORDER_ID,
                USER_ID,
                "RAFFLE",
                null,
                PRODUCT_ID,
                RAFFLE_ID,
                ENTRY_ID,
                30000L,
                3000L,
                27000L,
                COUPON_ID,
                "빌링키 아이디"
        );
    }
}
