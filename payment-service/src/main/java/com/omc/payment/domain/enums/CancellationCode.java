package com.omc.payment.domain.enums;

public enum CancellationCode {

    USER_CANCEL, // 유저 취소(환불)
    ADMIN_CANCEL, // 관리자 취소
    STOCK_DEDUCT_FAILED, // 재고 차감 실패
    HOLD_EXPIRED, // 재고 선점 만료
    USED_COUPON, // 이미 사용한 쿠폰
    NETWORK_CANCEL // 망 취소
}
