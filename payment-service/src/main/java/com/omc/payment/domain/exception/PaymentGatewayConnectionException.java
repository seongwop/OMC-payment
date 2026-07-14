package com.omc.payment.domain.exception;

public class PaymentGatewayConnectionException extends RuntimeException {
    /**
     * PG 연동 중 통신 실패 예외
     */
    public PaymentGatewayConnectionException(String message) {
        super(message);
    }

    public PaymentGatewayConnectionException(String message,  Throwable cause) {
        super(message, cause);
    }
}
