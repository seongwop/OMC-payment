package com.omc.paymenttools.verifier;

import com.omc.paymenttools.verifier.model.ObservedEvent;
import com.omc.paymenttools.verifier.model.OutboxSnapshot;
import com.omc.paymenttools.verifier.model.PaymentSnapshot;
import com.omc.paymenttools.verifier.model.VerificationOutcome;
import com.omc.paymenttools.verifier.model.VerificationReport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConsistencyVerifierService {

    private final PaymentSnapshotRepository paymentSnapshotRepository;
    private final ObservedEventStore observedEventStore;
    private final ConsistencyEvaluator consistencyEvaluator;

    @Value("${payment-test-tools.verification.default-timeout:15s}")
    private Duration defaultTimeout;

    @Value("${payment-test-tools.verification.default-poll-interval:200ms}")
    private Duration defaultPollInterval;

    // Payment DB, Outbox, Kafka 관찰 이벤트를 조합하여 현재 정합성 검증
    public VerificationReport verify(UUID orderId) {
        PaymentSnapshot payment = paymentSnapshotRepository.findPayment(orderId).orElse(null);
        List<OutboxSnapshot> outboxEvents = payment == null
                ? List.of()
                : paymentSnapshotRepository.findOutboxEvents(payment.paymentId());
        List<ObservedEvent> observedEvents = observedEventStore.findByOrderId(orderId);
        return consistencyEvaluator.evaluate(orderId, payment, outboxEvents, observedEvents);
    }

    // 최종 검증 결과 또는 제한 시간까지 정합성 상태 재조회
    public VerificationReport await(UUID orderId, Long timeoutMs, Long pollIntervalMs) {
        Duration timeout = timeoutMs == null ? defaultTimeout : Duration.ofMillis(timeoutMs);
        Duration pollInterval = pollIntervalMs == null
                ? defaultPollInterval
                : Duration.ofMillis(pollIntervalMs);
        validateDurations(timeout, pollInterval);

        long deadline = System.nanoTime() + timeout.toNanos();
        VerificationReport report = verify(orderId);
        while (!isFinal(report.outcome()) && System.nanoTime() < deadline) {
            sleep(pollInterval);
            report = verify(orderId);
        }
        return report;
    }

    // 테스트 실행 간 Kafka 관찰 결과 초기화
    public void clearObservations() {
        observedEventStore.clear();
    }

    private boolean isFinal(VerificationOutcome outcome) {
        return outcome == VerificationOutcome.CONSISTENT
                || outcome == VerificationOutcome.INCONSISTENT
                || outcome == VerificationOutcome.REQUIRES_ATTENTION;
    }

    private void validateDurations(Duration timeout, Duration pollInterval) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeoutMs는 0보다 커야 합니다.");
        }
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollIntervalMs는 0보다 커야 합니다.");
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("정합성 검증 대기 중 인터럽트가 발생했습니다.", e);
        }
    }
}
