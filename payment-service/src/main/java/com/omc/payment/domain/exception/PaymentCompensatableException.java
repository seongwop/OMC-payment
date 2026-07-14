package com.omc.payment.domain.exception;

import com.omc.common.exception.BusinessException;
import com.omc.common.exception.ErrorCode;

public class PaymentCompensatableException extends BusinessException {

    public PaymentCompensatableException(ErrorCode errorCode) {
        super(errorCode);
    }

    public PaymentCompensatableException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
