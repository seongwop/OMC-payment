package com.omc.payment.domain.exception;

import com.omc.common.exception.BusinessException;
import com.omc.common.exception.ErrorCode;

public class NonRetryablePaymentException extends BusinessException {

    public NonRetryablePaymentException(ErrorCode errorCode) {
        super(errorCode);
    }

    public NonRetryablePaymentException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
