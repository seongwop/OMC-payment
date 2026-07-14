package com.omc.payment.domain.exception;

import com.omc.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {


    // 404 NOT_FOUND
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT-001", "결제 정보를 찾을 수 없습니다."),

    // 400 BAD_REQUEST
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "PAYMENT-002", "결제 처리에 실패했습니다."),
    PAYMENT_INVALID_STATUS(HttpStatus.BAD_REQUEST, "PAYMENT-003", "현재 결제 상태에서 허용되지 않는 작업입니다."),
    PAYMENT_INVALID_COUPON(HttpStatus.BAD_REQUEST, "PAYMENT-008", "쿠폰 정보가 유효하지 않습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT-006", "결제 금액이 일치하지 않습니다."),

    // 409 CONFLICT
    PAYMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "PAYMENT-007", "이미 생성된 결제입니다."),

    // 502 BAD_GATEWAY
    PAYMENT_GATEWAY_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT-004", "결제 게이트웨이 요청에 실패했습니다."),

    // 503 SERVICE_UNAVAILABLE
    PAYMENT_GATEWAY_CONNECTION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT-005", "결제 게이트웨이와의 통신에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
