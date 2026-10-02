package com.macrodash.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseBody;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @DisplayName("QA-009: DB에 연결하지 못하면 500 '내부 오류'가 아니라 503과 DB 안내를 돌려준다")
    void databaseOutageIs503WithGuidance() throws Exception {
        // QA 스택에서 postgres를 멈추자 모든 데이터 API가 500 "서버 내부 오류가 발생했습니다. 백엔드 로그를
        // 확인하세요"로 답했습니다(화면도 그대로 표시). 코드 결함이 아니라 의존 서비스 장애이므로 503이어야
        // 하고, 사용자가 볼 곳(postgres)을 알려 줘야 합니다. /api/health는 이미 503 + database:unreachable입니다.
        var mvc = MockMvcBuilders.standaloneSetup(new DatabaseDownEndpoint())
                .setControllerAdvice(handler).build();

        mvc.perform(get("/boom"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("service_unavailable"))
                .andExpect(jsonPath("$.message", containsString("데이터베이스")))
                .andExpect(jsonPath("$.message", containsString("postgres")))
                .andExpect(jsonPath("$.message", not(containsString("JDBC"))));
    }

    // @RestController가 아니라 @Controller + @ResponseBody — RouteInventoryTest가 클래스패스의 @RestController를
    // 전부 세므로, 테스트용 컨트롤러가 실제 경로 목록에 섞이지 않게 합니다.
    @Controller
    static class DatabaseDownEndpoint {
        @GetMapping("/boom")
        @ResponseBody
        String boom() {
            throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                    new java.sql.SQLException("Connection refused"));
        }
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
