package com.omc.common.response;

// Validation 실패 시 ApiResponse.data 필드에 담기는 필드별 에러 상세
// { "field": "username", "value": "a", "reason": "4자 이상 입력해야 합니다." }
public record FieldErrorDetail(String field, String value, String reason) {
}
