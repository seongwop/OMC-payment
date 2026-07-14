package com.omc.payment.application.processor;

import com.omc.payment.domain.entity.PaymentInboxEvent;
import com.omc.payment.domain.exception.NonRetryablePaymentException;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.repository.PaymentInboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentInboxTransactionProcessor {

    private final PaymentInboxEventRepository paymentInboxEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createProcessing(String eventId, String topic) {
        paymentInboxEventRepository.saveAndFlush(PaymentInboxEvent.create(eventId, topic));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryIfFailed(String eventId) {
        PaymentInboxEvent inboxEvent = paymentInboxEventRepository.findById(eventId).orElse(null);
        if (inboxEvent == null || !inboxEvent.isFailed()) {
            return false;
        }
        inboxEvent.markProcessing();
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(String eventId) {
        PaymentInboxEvent inboxEvent = paymentInboxEventRepository.findById(eventId)
                .orElseThrow(() -> new NonRetryablePaymentException(
                        PaymentErrorCode.PAYMENT_FAILED,
                        "Inbox 이벤트가 존재하지 않습니다. eventId=" + eventId
                ));
        inboxEvent.markProcessed();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String eventId) {
        paymentInboxEventRepository.findById(eventId)
                .ifPresent(PaymentInboxEvent::markFailed);
    }
}
