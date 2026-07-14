package com.omc.payment.application.service;

import com.omc.payment.application.event.dto.inbound.OrderCreatedEvent;
import com.omc.payment.application.event.dto.inbound.RefundRequestedEvent;
import com.omc.payment.application.event.dto.inbound.StockFailedEvent;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.infrastructure.config.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventService {

    private final PaymentInboxService paymentInboxService;
    private final PaymentCoreService paymentCoreService;
    private final PaymentIdempotencyService paymentIdempotencyService;

    public void handleOrderCreated(OrderCreatedEvent event) {
        /*
        * Inbox로 eventId 기반 멱등성을 보장하고
        * 그 후에 다른 eventID의 같은 orderId 기반 멱등성을 Redis로 보장
        * Redis TTL이 지난 경우는 PaymentCoreService 내부 orderId 기반 DB 조회로 보장
        * */
        processInboxEvent(event.eventId(), KafkaTopics.ORDER_CREATED, () -> paymentIdempotencyService.execute(
                paymentIdempotencyService.confirmKey(event.orderId()),
                () -> {
                    // RAFFLE일 경우 빌링 키 결제, DROP일 경우 일반 결제
                    if ("RAFFLE".equalsIgnoreCase(event.orderType())) {
                        paymentCoreService.confirmBillingPayment(
                                event.orderId(),
                                event.entryId(),
                                event.raffleId(),
                                event.productId(),
                                event.couponId(),
                                event.userId(),
                                event.billingKeyId(),
                                null,
                                event.originalAmount(),
                                event.discountAmount(),
                                event.finalAmount()
                        );
                        return;
                    }

                    paymentCoreService.confirmPayment(
                            event.orderId(),
                            event.dropId(),
                            event.productId(),
                            event.couponId(),
                            event.userId(),
                            event.originalAmount(),
                            event.discountAmount(),
                            event.finalAmount(),
                            event.orderId().toString() // Mocking을 위한 orderId 결제 식별자
                    );
                }
        ));
    }

    public void handleRefundRequested(RefundRequestedEvent event) {
        processInboxEvent(event.eventId(), KafkaTopics.REFUND_REQUESTED, () -> paymentIdempotencyService.execute(
                paymentIdempotencyService.cancelKey(event.orderId()),
                () -> paymentCoreService.cancelPaymentByOrderId(
                        event.orderId(),
                        null,
                        event.reason()
                )
        ));
    }

    public void handleStockFailed(StockFailedEvent event) {
        processInboxEvent(event.eventId(), KafkaTopics.STOCK_FAILED, () -> paymentIdempotencyService.execute(
                paymentIdempotencyService.cancelKey(event.orderId()),
                () -> paymentCoreService.cancelPaymentByOrderId(
                        event.orderId(),
                        CancellationCode.STOCK_DEDUCT_FAILED,
                        "재고 차감 실패"
                )
        ));
    }

    private void processInboxEvent(String eventId, String topic, Runnable action) {
        if (paymentInboxService.isAlreadyProcessed(eventId, topic)) {
            log.info("이미 처리 중이거나 완료된 이벤트입니다. topic={}, eventId={}", topic, eventId);
            return;
        }

        try {
            action.run();
            paymentInboxService.markProcessed(eventId);
        } catch (RuntimeException e) {
            paymentInboxService.markFailed(eventId);
            throw e;
        }
    }
}
