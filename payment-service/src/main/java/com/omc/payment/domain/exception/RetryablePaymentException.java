package com.omc.payment.domain.exception;

import com.omc.common.exception.BusinessException;
import com.omc.common.exception.ErrorCode;

public class RetryablePaymentException extends BusinessException {

    public RetryablePaymentException(ErrorCode errorCode) {
        super(errorCode);
    }

    public RetryablePaymentException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
