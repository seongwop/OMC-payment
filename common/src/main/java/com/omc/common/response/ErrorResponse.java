package com.omc.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonPropertyOrder({"success", "status", "errorCode", "message", "data"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse<T> {

    private boolean success;
    private int status;
    private String errorCode;
    private String message;
    private T data;

    // 일반 에러 — GlobalExceptionHandler 전용
    public static ErrorResponse<Void> of(HttpStatus status, String errorCode, String message) {
        return new ErrorResponse<>(false, status.value(), errorCode, message, null);
    }

    // 필드 에러 포함 (Validation 등) — GlobalExceptionHandler 전용
    public static <T> ErrorResponse<T> of(HttpStatus status, String errorCode, String message, T data) {
        return new ErrorResponse<>(false, status.value(), errorCode, message, data);
    }
}
