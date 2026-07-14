package com.omc.common.response;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 응답 역직렬화 회귀 테스트.
 * ApiResponse<T>에 @JsonCreator 생성자가 없으면 getData()가 null을 반환하는 버그를 방지한다.
 */
class ApiResponseDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // [1] ApiResponse<String> 역직렬화 — data 필드 정상 복원
    // =========================================================================

    @Test
    void deserialize_stringData_returnsData() throws Exception {
        String json = """
                {"success":true,"status":200,"message":"OK","data":"hello"}
                """;

        ApiResponse<String> response = objectMapper.readValue(
                json, new TypeReference<ApiResponse<String>>() {});

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getMessage()).isEqualTo("OK");
        assertThat(response.getData()).isEqualTo("hello");
    }

    // =========================================================================
    // [2] ApiResponse<Map> 역직렬화 — 중첩 객체 data 필드 정상 복원
    // =========================================================================

    @Test
    void deserialize_mapData_returnsData() throws Exception {
        String json = """
                {"success":true,"status":200,"message":"OK","data":{"userId":"abc","slackId":"U123"}}
                """;

        ApiResponse<Map<String, String>> response = objectMapper.readValue(
                json, new TypeReference<ApiResponse<Map<String, String>>>() {});

        assertThat(response.getData()).isNotNull();
        assertThat(response.getData().get("userId")).isEqualTo("abc");
        assertThat(response.getData().get("slackId")).isEqualTo("U123");
    }

    // =========================================================================
    // [3] ApiResponse<Void> — data=null 역직렬화 허용
    // =========================================================================

    @Test
    void deserialize_nullData_returnsNull() throws Exception {
        String json = """
                {"success":true,"status":200,"message":"OK","data":null}
                """;

        ApiResponse<Void> response = objectMapper.readValue(
                json, new TypeReference<ApiResponse<Void>>() {});

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNull();
    }

    // =========================================================================
    // [4] 직렬화 → 역직렬화 왕복 일치 검증
    // =========================================================================

    @Test
    void serialize_thenDeserialize_roundTrip() throws Exception {
        ApiResponse<String> original = ApiResponse.success("test-value");

        String json = objectMapper.writeValueAsString(original);
        ApiResponse<String> restored = objectMapper.readValue(
                json, new TypeReference<ApiResponse<String>>() {});

        assertThat(restored.isSuccess()).isEqualTo(original.isSuccess());
        assertThat(restored.getStatus()).isEqualTo(original.getStatus());
        assertThat(restored.getMessage()).isEqualTo(original.getMessage());
        assertThat(restored.getData()).isEqualTo(original.getData());
    }
}
