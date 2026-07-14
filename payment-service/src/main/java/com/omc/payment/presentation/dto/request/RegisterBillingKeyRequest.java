package com.omc.payment.presentation.dto.request;

public record RegisterBillingKeyRequest(
        /* 클라이언트 서버가 없으므로 검증하지 않고 테스트를 위해 서버에서 랜덤값 생성 */
        String customerKey,
        String authKey
) {
}
