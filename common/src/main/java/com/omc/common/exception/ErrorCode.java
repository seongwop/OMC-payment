package com.omc.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 모든 에러 코드의 공통 규격.
 *
 * 각 서비스 모듈에서 이 interface를 구현하는 enum을 만들어 사용한다.
 *
 * 구현 예시 (user-service):
 *
 *   @Getter
 *   @RequiredArgsConstructor
 *   public enum UserErrorCode implements ErrorCode {
 *       USER_NOT_FOUND(HttpStatus.NOT_FOUND,     "USER-001", "사용자를 찾을 수 없습니다."),
 *       USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-002", "이미 존재하는 사용자입니다."),
 *       USER_NOT_APPROVED(HttpStatus.FORBIDDEN,  "USER-003", "승인되지 않은 사용자입니다.");
 *
 *       private final HttpStatus status;
 *       private final String code;
 *       private final String message;
 *   }
 *
 * 사용 예시 (service 레이어):
 *
 *   throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
 *
 * GlobalExceptionHandler가 BusinessException을 잡아 ErrorCode 기반으로 응답을 내려준다.
 */
public interface ErrorCode {
    HttpStatus getStatus();
    String getCode();
    String getMessage();
    // 특정 필드에 대한 에러일 경우 override해서 필드명 반환 (기본값 null)
    default String getField() {
        return null;
    }
}
