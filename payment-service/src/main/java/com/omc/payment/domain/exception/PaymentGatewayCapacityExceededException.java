package com.omc.payment.domain.exception;

public class PaymentGatewayCapacityExceededException extends RuntimeException {

    public PaymentGatewayCapacityExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
