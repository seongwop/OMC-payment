package com.omc.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    // 001~899: 일반 오류
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "COMMON-001", "잘못된 입력값입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "COMMON-002", "해당 작업을 수행할 권한이 없습니다."),
    INVALID_PARAMETER_TYPE(HttpStatus.BAD_REQUEST, "COMMON-003", "잘못된 파라미터 타입입니다."),
    MISSING_PATH_VARIABLE(HttpStatus.BAD_REQUEST, "COMMON-004", "필수 경로 변수가 누락되었습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-005", "지원하지 않는 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-006", "서버 내부 오류가 발생했습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON-007", "인증이 필요합니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-008", "요청한 리소스를 찾을 수 없습니다."),

    // 900~999: 외부 연동 오류
    REMOTE_CALL_FAILED(HttpStatus.BAD_GATEWAY, "COMMON-998", "외부 서비스 오류가 발생했습니다."),
    REMOTE_RESPONSE_PARSE_ERROR(HttpStatus.BAD_GATEWAY, "COMMON-999", "외부 서비스 응답 파싱에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
