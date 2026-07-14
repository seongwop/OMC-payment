package com.omc.payment.domain.enums;

public enum PaymentReconciliationResultType {
    STATUS_MISMATCH, // 상태 불일치
    AMOUNT_MISMATCH, // 금액 불일치
    ORDER_ID_MISMATCH, // 주문 ID 불일치
    PG_LOOKUP_FAILED // PG 조회 실패
}