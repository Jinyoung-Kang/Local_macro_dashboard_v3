package com.macrodash.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공통 오류 응답 — 통합 테스트로 만들기 어려운 경우(예상 못 한 500, 연결 끊김)를 직접 확인합니다.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/example");

    @Test
    @DisplayName("예상하지 못한 예외는 500과 일반 문구만 — 원인 문구(SQL 등)를 내보내지 않는다")
    void unexpectedExceptionHidesInternals() {
        ResponseEntity<ApiExceptionHandler.ApiError> response = handler.unexpected(
                new IllegalStateException("PreparedStatementCallback; SQL [SELECT * FROM secret]"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("internal_server_error");
        assertThat(response.getBody().message()).doesNotContain("SQL", "SELECT", "secret");
    }

    @Test
    @DisplayName("ResponseStatusException은 상태와 우리가 쓴 사유를 그대로 쓴다")
    void responseStatusExceptionKeepsOurReason() {
        ResponseEntity<ApiExceptionHandler.ApiError> response = handler.unexpected(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "추가 지시은(는) 2000자 이하로 입력하세요"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("bad_request");
        assertThat(response.getBody().message()).startsWith("추가 지시");
    }

    @Test
    @DisplayName("클라이언트가 연결을 끊었으면 아무것도 쓰지 않는다(오류 로그도 남기지 않음)")
    void clientDisconnectWritesNothing() {
        assertThat(handler.unexpected(new AsyncRequestNotUsableException("Broken pipe"), request)).isNull();
    }

    @Test
    @DisplayName("오류 코드는 HTTP 상태 이름의 소문자 — 세션 필터의 unauthorized와 같은 규칙")
    void errorCodesFollowStatusNames() {
        assertThat(ApiExceptionHandler.errorCode(401)).isEqualTo("unauthorized");
        assertThat(ApiExceptionHandler.errorCode(404)).isEqualTo("not_found");
        assertThat(ApiExceptionHandler.errorCode(502)).isEqualTo("bad_gateway");
        assertThat(ApiExceptionHandler.errorCode(599)).isEqualTo("error");
    }
}
