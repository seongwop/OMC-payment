package com.omc.common.handler;

import com.omc.common.exception.BusinessException;
import com.omc.common.exception.CommonErrorCode;
import com.omc.common.exception.ErrorCode;
import com.omc.common.response.ErrorResponse;
import com.omc.common.response.FieldErrorDetail;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode.getStatus(), errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse<List<FieldErrorDetail>>> handleValidationException(MethodArgumentNotValidException e) {
        List<FieldErrorDetail> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDetail(
                        error.getField(),
                        String.valueOf(error.getRejectedValue()),
                        error.getDefaultMessage()
                ))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        CommonErrorCode.INVALID_INPUT_VALUE.getCode(),
                        CommonErrorCode.INVALID_INPUT_VALUE.getMessage(),
                        fieldErrors
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse<Void>> handleException(Exception e) {
        log.error("Server Error: ", e);
        Sentry.captureException(e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        CommonErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                        CommonErrorCode.INTERNAL_SERVER_ERROR.getMessage()
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn(e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        CommonErrorCode.INVALID_INPUT_VALUE.getCode(),
                        "JSON 형식이 올바르지 않습니다."
                ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        Sentry.captureException(e);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(
                        HttpStatus.FORBIDDEN,
                        CommonErrorCode.ACCESS_DENIED.getCode(),
                        CommonErrorCode.ACCESS_DENIED.getMessage()
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse<List<FieldErrorDetail>>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e) {
        log.warn("Parameter Type Mismatch: {}", e.getMessage());
        List<FieldErrorDetail> fieldErrors = List.of(new FieldErrorDetail(
                e.getName(),
                String.valueOf(e.getValue()),
                String.format("'%s'은(는) 유효한 %s 형식이 아닙니다.", e.getValue(), e.getRequiredType().getSimpleName())
        ));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        CommonErrorCode.INVALID_PARAMETER_TYPE.getCode(),
                        CommonErrorCode.INVALID_PARAMETER_TYPE.getMessage(),
                        fieldErrors
                ));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse<Void>> handleMissingRequestHeaderException(MissingRequestHeaderException e) {
        log.warn("Missing Request Header: {}", e.getHeaderName());
        Sentry.captureException(e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        CommonErrorCode.INVALID_INPUT_VALUE.getCode(),
                        String.format("필수 헤더가 누락되었습니다: %s", e.getHeaderName())
                ));
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<ErrorResponse<List<FieldErrorDetail>>> handleMissingPathVariableException(MissingPathVariableException e) {
        log.warn("Missing Path Variable: {}", e.getMessage());
        List<FieldErrorDetail> fieldErrors = List.of(new FieldErrorDetail(
                e.getVariableName(), null, "필수 경로 변수가 누락되었습니다."
        ));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        CommonErrorCode.MISSING_PATH_VARIABLE.getCode(),
                        CommonErrorCode.MISSING_PATH_VARIABLE.getMessage(),
                        fieldErrors
                ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse<List<FieldErrorDetail>>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e) {
        List<FieldErrorDetail> fieldErrors = List.of(new FieldErrorDetail(
                e.getParameterName(), null, "필수 쿼리 파라미터가 누락되었습니다."
        ));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST,
                        CommonErrorCode.INVALID_INPUT_VALUE.getCode(),
                        "필수 쿼리 파라미터가 누락되었습니다.",
                        fieldErrors
                ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse<Void>> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e) {
        log.warn("Method Not Allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of(
                        HttpStatus.METHOD_NOT_ALLOWED,
                        CommonErrorCode.METHOD_NOT_ALLOWED.getCode(),
                        CommonErrorCode.METHOD_NOT_ALLOWED.getMessage()
                ));
    }
}
