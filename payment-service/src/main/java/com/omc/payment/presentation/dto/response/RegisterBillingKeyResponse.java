package com.omc.payment.presentation.dto.response;

public record RegisterBillingKeyResponse(
        String billingKeyId
) {
    public static RegisterBillingKeyResponse from(String billingKeyId) {
        return new RegisterBillingKeyResponse(billingKeyId);
    }
}
