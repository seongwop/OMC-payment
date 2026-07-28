package com.omc.paymenttools.verifier;

import com.omc.paymenttools.verifier.model.VerificationReport;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/test/verifications")
@ConditionalOnProperty(
        prefix = "payment-test-tools.verifier",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
public class ConsistencyVerifierController {

    private final ConsistencyVerifierService consistencyVerifierService;

    // 현재 시점 결제 정합성 조회
    @GetMapping("/orders/{orderId}")
    public VerificationReport verify(@PathVariable UUID orderId) {
        return consistencyVerifierService.verify(orderId);
    }

    // 최종 판정 또는 제한 시간까지 결제 정합성 조회
    @GetMapping("/orders/{orderId}/await")
    public VerificationReport await(
            @PathVariable UUID orderId,
            @RequestParam(required = false) Long timeoutMs,
            @RequestParam(required = false) Long pollIntervalMs
    ) {
        return consistencyVerifierService.await(orderId, timeoutMs, pollIntervalMs);
    }

    // 테스트 실행 간 Kafka 관찰 결과 초기화
    @DeleteMapping("/observations")
    public ResponseEntity<Void> clearObservations() {
        consistencyVerifierService.clearObservations();
        return ResponseEntity.noContent().build();
    }
}
