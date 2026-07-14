package com.omc.payment.domain.enums;

public enum OutboxEventStatus {
    INIT,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    DEAD
}
