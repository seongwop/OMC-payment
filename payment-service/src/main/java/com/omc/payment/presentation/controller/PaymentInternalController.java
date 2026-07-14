package com.omc.payment.presentation.controller;

import com.omc.payment.application.service.PaymentService;
import com.omc.payment.presentation.dto.request.ConfirmPaymentRequest;
import com.omc.payment.presentation.dto.request.RegisterBillingKeyRequest;
import com.omc.payment.presentation.dto.response.PaymentResponse;
import com.omc.payment.presentation.dto.response.RegisterBillingKeyResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1")
@Tag(name = "Internal Payment", description = "서비스 간 내부 호출 API")
public class PaymentInternalController {

    private final PaymentService paymentService;

    /*
     * 클라이언트 통신용 INTERNAL API
     */

    // 클라이언트가 결제창/SDK 리다이렉트로 받은 providerPaymentId
    @PostMapping("/payments/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse confirmPayment(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        return paymentService.confirmPayment(request, userId);
    }

    // 클라이언트가 결제창/SDK 리다이렉트로 받은 customerKey, authKey
    @PostMapping("/payments/pre-auth")
    public RegisterBillingKeyResponse registerBillingKey(
            @RequestBody RegisterBillingKeyRequest request
    ) {
        return paymentService.registerBillingKey(request);
    }
}
