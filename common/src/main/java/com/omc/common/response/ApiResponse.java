package com.omc.common.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@JsonPropertyOrder({"success", "status", "message", "data"})
public class ApiResponse<T> {

    private boolean success;
    private int status;
    private String message;
    private T data;

    @JsonCreator
    private ApiResponse(
            @JsonProperty("success") boolean success,
            @JsonProperty("status")  int status,
            @JsonProperty("message") String message,
            @JsonProperty("data")    T data
    ) {
        this.success = success;
        this.status  = status;
        this.message = message;
        this.data    = data;
    }

    // 데이터 있는 성공 응답 — return ResponseEntity.ok(ApiResponse.success(data));
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, 200, "OK", data);
    }

    // 데이터 없는 성공 응답 — return ResponseEntity.ok(ApiResponse.ok());
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, 200, "OK", null);
    }

    // 커스텀 메시지 — return ResponseEntity.ok(ApiResponse.success("삭제되었습니다.", data));
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, 200, message, data);
    }

    // 201 Created — return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(data));
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, 201, "CREATED", data);
    }
}
