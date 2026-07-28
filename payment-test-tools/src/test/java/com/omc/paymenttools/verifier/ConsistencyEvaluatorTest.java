package com.omc.paymenttools.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.paymenttools.config.KafkaTopics;
import com.omc.paymenttools.verifier.model.ObservedEvent;
import com.omc.paymenttools.verifier.model.OutboxSnapshot;
import com.omc.paymenttools.verifier.model.PaymentSnapshot;
import com.omc.paymenttools.verifier.model.VerificationOutcome;
import com.omc.paymenttools.verifier.model.VerificationReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyEvaluatorTest {

    private final ConsistencyEvaluator evaluator = new ConsistencyEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void paidPaymentIsConsistentWhenCompletedEventWasObserved() {
        UUID orderId = UUID.randomUUID();
        PaymentSnapshot payment = payment(orderId, "PAID");
        ObservedEvent event = observedEvent(orderId, KafkaTopics.PAYMENT_COMPLETED);

        VerificationReport report = evaluator.evaluate(orderId, payment, List.of(), List.of(event));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.CONSISTENT);
        assertThat(report.expectedEvent()).isEqualTo(KafkaTopics.PAYMENT_COMPLETED);
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void finalStateStaysPendingWhileExpectedOutboxEventHasNotBeenObserved() {
        UUID orderId = UUID.randomUUID();
        PaymentSnapshot payment = payment(orderId, "PAID");
        OutboxSnapshot outbox = new OutboxSnapshot(
                UUID.randomUUID(),
                KafkaTopics.PAYMENT_COMPLETED,
                "INIT",
                0,
                LocalDateTime.now(),
                null
        );

        VerificationReport report = evaluator.evaluate(orderId, payment, List.of(outbox), List.of());

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.PENDING);
        assertThat(report.issues()).anyMatch(issue -> issue.contains("Outbox"));
    }

    @Test
    void unknownPaymentIsReportedAsPendingRecovery() {
        UUID orderId = UUID.randomUUID();
        PaymentSnapshot payment = payment(orderId, "CONFIRM_UNKNOWN");

        VerificationReport report = evaluator.evaluate(orderId, payment, List.of(), List.of());

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.PENDING);
        assertThat(report.expectedEvent()).isNull();
    }

    @Test
    void conflictingCompletedAndFailedEventsAreInconsistent() {
        UUID orderId = UUID.randomUUID();
        PaymentSnapshot payment = payment(orderId, "PAID");
        List<ObservedEvent> events = List.of(
                observedEvent(orderId, KafkaTopics.PAYMENT_COMPLETED),
                observedEvent(orderId, KafkaTopics.PAYMENT_FAILED)
        );

        VerificationReport report = evaluator.evaluate(orderId, payment, List.of(), events);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.INCONSISTENT);
        assertThat(report.issues()).anyMatch(issue -> issue.contains("함께 관찰"));
    }

    @Test
    void deadOutboxRequiresAttention() {
        UUID orderId = UUID.randomUUID();
        PaymentSnapshot payment = payment(orderId, "FAILED");
        OutboxSnapshot outbox = new OutboxSnapshot(
                UUID.randomUUID(),
                KafkaTopics.PAYMENT_FAILED,
                "DEAD",
                3,
                LocalDateTime.now(),
                null
        );

        VerificationReport report = evaluator.evaluate(orderId, payment, List.of(outbox), List.of());

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.REQUIRES_ATTENTION);
    }

    private PaymentSnapshot payment(UUID orderId, String status) {
        return new PaymentSnapshot(
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                status,
                orderId.toString(),
                null,
                0,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private ObservedEvent observedEvent(UUID orderId, String topic) {
        return new ObservedEvent(
                topic,
                UUID.randomUUID().toString(),
                orderId,
                objectMapper.createObjectNode().put("orderId", orderId.toString()),
                Instant.now()
        );
    }
}
