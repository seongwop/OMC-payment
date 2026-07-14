package com.omc.payment.domain.exception;

import lombok.Getter;


@Getter
public class PaymentGatewayRequestException extends RuntimeException {
    /**
     * PG 연동사로부터 받은 실패 응답
     * 에러 코드와 메시지를 포함한 정상 에러 응답 반환 예외
     */
    private final String providerErrorCode;

    public PaymentGatewayRequestException(String providerErrorCode, String message) {
        super(message);
        this.providerErrorCode = providerErrorCode;
    }
}
