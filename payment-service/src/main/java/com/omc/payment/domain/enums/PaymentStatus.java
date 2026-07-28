package com.omc.payment.domain.enums;

public enum PaymentStatus {
    READY,
    CONFIRMING,
    PAID,
    FAILED,
    CANCELED,
    CONFIRM_UNKNOWN, // 망 취소, 타임 아웃, 연동 장애
    CANCEL_UNKNOWN,
    RECOVERY_FAILED;

    public boolean canChangeTo(PaymentStatus next) {
        return switch (this) {
            case READY -> next == CONFIRMING
                    || next == FAILED
                    || next == CANCELED
                    || next == CONFIRM_UNKNOWN
                    || next == CANCEL_UNKNOWN;
            case CONFIRMING -> next == READY
                    || next == PAID
                    || next == FAILED
                    || next == CONFIRM_UNKNOWN;
            case PAID -> next == CANCELED || next == CANCEL_UNKNOWN;
            case CONFIRM_UNKNOWN -> next == PAID
                    || next == FAILED
                    || next == CANCELED
                    || next == CANCEL_UNKNOWN
                    || next == RECOVERY_FAILED;
            case CANCEL_UNKNOWN -> next == PAID || next == CANCELED || next == RECOVERY_FAILED;
            case FAILED, CANCELED, RECOVERY_FAILED -> false;
        };
    }
}
