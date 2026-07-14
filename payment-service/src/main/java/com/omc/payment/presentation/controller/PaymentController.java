package com.omc.payment.presentation.controller;

import com.omc.common.response.ApiResponse;
import com.omc.common.response.PageResponse;
import com.omc.payment.application.service.PaymentService;
import com.omc.payment.presentation.dto.request.ConfirmPaymentRequest;
import com.omc.payment.presentation.dto.request.CancelPaymentRequest;
import com.omc.payment.presentation.dto.request.RegisterBillingKeyRequest;
import com.omc.payment.presentation.dto.response.PaymentDetailResponse;
import com.omc.payment.presentation.dto.response.PaymentResponse;
import com.omc.payment.presentation.dto.response.RegisterBillingKeyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
@Tag(name = "Payment", description = "결제 조회 및 취소 API")
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "결제 취소")
    @PostMapping("/payments/{paymentId}/cancel")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ApiResponse<PaymentResponse> cancelPayment(
            @PathVariable UUID paymentId,
            @Valid @RequestBody CancelPaymentRequest request
    ) {
        return ApiResponse.success(paymentService.cancelPayment(paymentId, request));
    }

    @Operation(summary = "내 결제 조회")
    @GetMapping("/payments/me")
    @PreAuthorize("hasRole('USER')")
    public ApiResponse<PageResponse<PaymentDetailResponse>> getMyPayments(
            @ParameterObject
            @PageableDefault(size = 10, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(paymentService.getMyPayments(pageable));
    }

    @Operation(summary = "전체 결제 조회")
    @GetMapping("/admin/payments")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<PaymentDetailResponse>> getPayments(
            @ParameterObject
            @PageableDefault(size = 10, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(paymentService.getPayments(pageable));
    }
}
