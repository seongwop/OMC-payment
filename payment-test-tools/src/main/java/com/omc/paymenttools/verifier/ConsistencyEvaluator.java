package com.omc.paymenttools.verifier;

import com.omc.paymenttools.config.KafkaTopics;
import com.omc.paymenttools.verifier.model.ObservedEvent;
import com.omc.paymenttools.verifier.model.OutboxSnapshot;
import com.omc.paymenttools.verifier.model.PaymentSnapshot;
import com.omc.paymenttools.verifier.model.VerificationOutcome;
import com.omc.paymenttools.verifier.model.VerificationReport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ConsistencyEvaluator {

    private static final List<String> IN_PROGRESS_STATUSES = List.of(
            "READY",
            "CONFIRMING",
            "CONFIRM_UNKNOWN",
            "CANCEL_UNKNOWN"
    );

    public VerificationReport evaluate(
            UUID orderId,
            PaymentSnapshot payment,
            List<OutboxSnapshot> outboxEvents,
            List<ObservedEvent> observedEvents
    ) {
        // 결제 생성 여부 확인
        if (payment == null) {
            return report(
                    orderId,
                    VerificationOutcome.NOT_FOUND,
                    null,
                    null,
                    outboxEvents,
                    observedEvents,
                    List.of("해당 주문의 결제가 아직 생성되지 않았습니다.")
            );
        }

        // 관찰된 이벤트의 중복 및 상충 여부 확인
        List<String> issues = new ArrayList<>();
        Map<String, Long> eventCounts = observedEvents.stream()
                .collect(Collectors.groupingBy(ObservedEvent::topic, Collectors.counting()));
        addDuplicateIssues(eventCounts, issues);
        addConflictingEventIssues(payment.paymentStatus(), eventCounts, issues);

        String expectedEvent = expectedEvent(payment.paymentStatus());
        if (!issues.isEmpty()) {
            return report(
                    orderId,
                    VerificationOutcome.INCONSISTENT,
                    expectedEvent,
                    payment,
                    outboxEvents,
                    observedEvents,
                    issues
            );
        }

        // 미확정 결제 상태의 후속 처리 대기 여부 확인
        if (IN_PROGRESS_STATUSES.contains(payment.paymentStatus())) {
            issues.add("결제가 복구 또는 최종 상태 전이를 기다리고 있습니다: " + payment.paymentStatus());
            return report(
                    orderId,
                    VerificationOutcome.PENDING,
                    null,
                    payment,
                    outboxEvents,
                    observedEvents,
                    issues
            );
        }

        // 자동 복구 최대 횟수 초과 여부 확인
        if ("RECOVERY_FAILED".equals(payment.paymentStatus())) {
            issues.add("자동 복구 최대 횟수를 초과하여 운영자 확인이 필요합니다.");
            return report(
                    orderId,
                    VerificationOutcome.REQUIRES_ATTENTION,
                    null,
                    payment,
                    outboxEvents,
                    observedEvents,
                    issues
            );
        }

        if (expectedEvent == null) {
            issues.add("지원하지 않는 결제 상태입니다: " + payment.paymentStatus());
            return report(
                    orderId,
                    VerificationOutcome.INCONSISTENT,
                    null,
                    payment,
                    outboxEvents,
                    observedEvents,
                    issues
            );
        }

        // 최종 결제 상태에 대응하는 Kafka 이벤트 발행 여부 확인
        if (eventCounts.getOrDefault(expectedEvent, 0L) == 1L) {
            return report(
                    orderId,
                    VerificationOutcome.CONSISTENT,
                    expectedEvent,
                    payment,
                    outboxEvents,
                    observedEvents,
                    List.of()
            );
        }

        List<OutboxSnapshot> expectedOutboxEvents = outboxEvents.stream()
                .filter(event -> expectedEvent.equals(event.eventType()))
                .toList();

        // 발행되지 않은 기대 이벤트의 Outbox 상태 확인
        if (expectedOutboxEvents.stream().anyMatch(event -> "DEAD".equals(event.status()))) {
            issues.add("필요한 이벤트가 Outbox DEAD 상태로 전환되었습니다: " + expectedEvent);
            return report(
                    orderId,
                    VerificationOutcome.REQUIRES_ATTENTION,
                    expectedEvent,
                    payment,
                    outboxEvents,
                    observedEvents,
                    issues
            );
        }

        if (!expectedOutboxEvents.isEmpty()) {
            issues.add("필요한 이벤트가 Outbox에 있지만 아직 Kafka에서 관찰되지 않았습니다: " + expectedEvent);
            return report(
                    orderId,
                    VerificationOutcome.PENDING,
                    expectedEvent,
                    payment,
                    outboxEvents,
                    observedEvents,
                    issues
            );
        }

        issues.add("최종 결제 상태에 대응하는 Outbox 또는 Kafka 이벤트가 없습니다: " + expectedEvent);
        return report(
                orderId,
                VerificationOutcome.INCONSISTENT,
                expectedEvent,
                payment,
                outboxEvents,
                observedEvents,
                issues
        );
    }

    // 동일 토픽에 서로 다른 eventId가 발행된 중복 이벤트 확인
    private void addDuplicateIssues(Map<String, Long> eventCounts, List<String> issues) {
        eventCounts.forEach((topic, count) -> {
            if (count > 1) {
                issues.add("동일 토픽에서 서로 다른 이벤트가 여러 건 관찰되었습니다. topic="
                        + topic + ", count=" + count);
            }
        });
    }

    // 현재 결제 상태와 상충하는 발행 이벤트 조합 확인
    private void addConflictingEventIssues(
            String paymentStatus,
            Map<String, Long> eventCounts,
            List<String> issues
    ) {
        boolean completed = eventCounts.containsKey(KafkaTopics.PAYMENT_COMPLETED);
        boolean failed = eventCounts.containsKey(KafkaTopics.PAYMENT_FAILED);
        boolean refunded = eventCounts.containsKey(KafkaTopics.REFUND_DONE);

        if (completed && failed) {
            issues.add("payment.completed와 payment.failed 이벤트가 함께 관찰되었습니다.");
        }
        if ("PAID".equals(paymentStatus) && (failed || refunded)) {
            issues.add("PAID 결제에 실패 또는 환불 이벤트가 함께 관찰되었습니다.");
        }
        if ("FAILED".equals(paymentStatus) && (completed || refunded)) {
            issues.add("FAILED 결제에 승인 완료 또는 환불 이벤트가 함께 관찰되었습니다.");
        }
        if ("CANCELED".equals(paymentStatus) && failed) {
            issues.add("CANCELED 결제에 payment.failed 이벤트가 함께 관찰되었습니다.");
        }
    }

    // 최종 결제 상태별로 발행되어야 하는 이벤트 결정
    private String expectedEvent(String paymentStatus) {
        return switch (paymentStatus) {
            case "PAID" -> KafkaTopics.PAYMENT_COMPLETED;
            case "FAILED" -> KafkaTopics.PAYMENT_FAILED;
            case "CANCELED" -> KafkaTopics.REFUND_DONE;
            default -> null;
        };
    }

    private VerificationReport report(
            UUID orderId,
            VerificationOutcome outcome,
            String expectedEvent,
            PaymentSnapshot payment,
            List<OutboxSnapshot> outboxEvents,
            List<ObservedEvent> observedEvents,
            List<String> issues
    ) {
        return new VerificationReport(
                orderId,
                outcome,
                expectedEvent,
                payment,
                List.copyOf(outboxEvents),
                List.copyOf(observedEvents),
                List.copyOf(issues),
                Instant.now()
        );
    }
}
