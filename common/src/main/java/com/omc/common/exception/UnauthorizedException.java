package com.omc.common.exception;

public class UnauthorizedException extends BusinessException {
    public UnauthorizedException() {
        super(CommonErrorCode.UNAUTHORIZED);
    }
}
