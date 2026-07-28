package com.omc.paymenttools.verifier.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VerificationReport(
        UUID orderId,
        VerificationOutcome outcome,
        String expectedEvent,
        PaymentSnapshot payment,
        List<OutboxSnapshot> outboxEvents,
        List<ObservedEvent> observedEvents,
        List<String> issues,
        Instant verifiedAt
) {
}
