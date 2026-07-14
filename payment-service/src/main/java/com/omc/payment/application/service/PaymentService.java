package com.omc.payment.application.service;

import com.omc.common.exception.BusinessException;
import com.omc.common.exception.CommonErrorCode;
import com.omc.common.response.PageResponse;
import com.omc.common.security.SecurityUtil;
import com.omc.common.util.PageableUtil;
import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayPort;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.exception.PaymentGatewayConnectionException;
import com.omc.payment.domain.exception.PaymentGatewayRequestException;
import com.omc.payment.domain.repository.PaymentRepository;
import com.omc.payment.presentation.dto.request.CancelPaymentRequest;
import com.omc.payment.presentation.dto.request.ConfirmPaymentRequest;
import com.omc.payment.presentation.dto.request.RegisterBillingKeyRequest;
import com.omc.payment.presentation.dto.response.PaymentDetailResponse;
import com.omc.payment.presentation.dto.response.PaymentResponse;
import com.omc.payment.presentation.dto.response.RegisterBillingKeyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor

public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentCoreService paymentCoreService;
    private final PaymentIdempotencyService paymentIdempotencyService;

    public PaymentResponse confirmPayment(ConfirmPaymentRequest request, UUID userId) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        if (request == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        Payment payment = paymentIdempotencyService.execute(
                paymentIdempotencyService.confirmKey(request.orderID()),
                () -> paymentCoreService.confirmPayment(
                        request.orderID(),
                        request.dropId(),
                        request.productId(),
                        request.couponID(),
                        userId,
                        request.originalAmount(),
                        request.discountAmount(),
                        request.finalAmount(),
                        request.providerPaymentId()
                ),
                () -> paymentRepository.findByOrderId(request.orderID())
                        .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND))
        );
        return PaymentResponse.from(payment);
    }

    public RegisterBillingKeyResponse registerBillingKey(RegisterBillingKeyRequest request) {
        try {
            if (request == null) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT_VALUE);
            }
            /*
            * Mocking을 위한 랜덤 키 Fallback
            * */
            String customerKey = request.customerKey() == null || request.customerKey().isBlank()
                    ? UUID.randomUUID().toString()
                    : request.customerKey();

            String authKey = request.authKey() == null || request.authKey().isBlank()
                    ? UUID.randomUUID().toString()
                    : request.authKey();

            PaymentGatewayResult.RegisterBillingKey result = paymentGatewayPort.registerBillingKey(
                    new PaymentGatewayCommand.RegisterBillingKey(customerKey, authKey)
            );
            return RegisterBillingKeyResponse.from(result.billingKeyID());
        } catch (PaymentGatewayRequestException e) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_REQUEST_FAILED, e.getMessage());
        } catch (PaymentGatewayConnectionException e) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_GATEWAY_CONNECTION_FAILED, e.getMessage());
        }
    }

    public PaymentResponse cancelPayment(UUID paymentId, CancelPaymentRequest request) {
        if (request == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        Payment payment = paymentCoreService.cancelPaymentByPaymentId(
                paymentId,
                getCurrentUserId(),
                getCurrentUserRole(),
                request.cancellationCode(),
                request.cancelReason()
        );
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentDetailResponse> getMyPayments(Pageable pageable) {
        Pageable validatedPageable = PageableUtil.validatePageSize(pageable);
        UUID currentUserId = getCurrentUserId();
        Page<PaymentDetailResponse> page = paymentRepository.findAllByUserId(currentUserId, validatedPageable)
                .map(PaymentDetailResponse::from);
        return new PageResponse<>(page);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentDetailResponse> getPayments(Pageable pageable) {
        Pageable validatedPageable = PageableUtil.validatePageSize(pageable);
        Page<PaymentDetailResponse> page = paymentRepository.findAll(validatedPageable)
                .map(PaymentDetailResponse::from);
        return new PageResponse<>(page);
    }

    private UUID getCurrentUserId() {
        return SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
    }

    private String getCurrentUserRole() {
        return SecurityUtil.getCurrentUserRole()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
    }
}
