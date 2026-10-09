package com.macrodash.web;

import com.macrodash.feature.auth.AccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경로별 접근 정책 — 컨트롤러가 여는 모든 경로가 표에 있고, 운영 경로는 운영자만(ADR 0010).
 */
class AccessPolicyTest {

    @Test
    @DisplayName("컨트롤러가 여는 경로가 접근 정책 표와 정확히 같다 — 새 경로는 누구에게 열지 정해야 빌드가 통과한다")
    void everyRouteIsClassified() throws ClassNotFoundException {
        assertThat(AccessPolicy.routes().keySet()).containsExactlyInAnyOrderElementsOf(RouteInventoryTest.declaredRoutes());
    }

    @Test
    @DisplayName("수집을 일으키거나 내부 상태를 보여 주는 경로는 운영자만, 로그인·가입·재설정·헬스체크만 공개")
    void sensitiveRoutesAreOwnerOnly() {
        assertThat(AccessPolicy.required("POST", "/api/status/refresh")).isEqualTo(AccessPolicy.Access.OWNER);
        assertThat(AccessPolicy.required("POST", "/api/status/run/fred_series")).isEqualTo(AccessPolicy.Access.OWNER);
        assertThat(AccessPolicy.required("POST", "/api/ai/test")).isEqualTo(AccessPolicy.Access.OWNER);
        assertThat(AccessPolicy.required("GET", "/api/status/public-apis")).isEqualTo(AccessPolicy.Access.OWNER);
        assertThat(AccessPolicy.required("GET", "/api/macro/fred/T10Y2Y")).isEqualTo(AccessPolicy.Access.MEMBER);
        assertThat(AccessPolicy.required("GET", "/api/macro/fred/T10Y2Y/extra")).as("표에 없으면 로그인만")
                .isEqualTo(AccessPolicy.Access.MEMBER);
        assertThat(AccessPolicy.routes().entrySet().stream()
                .filter(entry -> entry.getValue() == AccessPolicy.Access.PUBLIC).map(java.util.Map.Entry::getKey))
                .containsExactlyInAnyOrder("GET /api/health", "POST /api/auth/login", "GET /api/auth/session",
                        "POST /api/auth/signup", "POST /api/auth/verify", "POST /api/auth/reset/request",
                        "POST /api/auth/reset/confirm");
    }

    @Test
    @DisplayName("운영자는 모든 등급 경로를, 무료 회원은 운영·유료 경로를 못 쓴다")
    void allows() {
        assertThat(AccessPolicy.allows(AccessPolicy.Access.OWNER, "MEMBER", "PRO")).isFalse();
        assertThat(AccessPolicy.allows(AccessPolicy.Access.PRO, "MEMBER", "FREE")).isFalse();
        assertThat(AccessPolicy.allows(AccessPolicy.Access.PRO, "MEMBER", "PRO")).isTrue();
        assertThat(AccessPolicy.allows(AccessPolicy.Access.PRO, "OWNER", "FREE")).isTrue();
    }

    @Test
    @DisplayName("유료 경로: AI 리포트·종목별 수급·13F 신규 매수·포트폴리오 위험. 사용자 관리는 운영자만")
    void paidAndAdminRoutes() {
        for (String route : java.util.List.of("POST /api/ai/report", "GET /api/kr/stock-flows",
                "GET /api/sec13f/new-buys", "GET /api/guru/risk")) {
            String[] parts = route.split(" ");
            assertThat(AccessPolicy.required(parts[0], parts[1])).as(route).isEqualTo(AccessPolicy.Access.PRO);
        }
        assertThat(AccessPolicy.required("POST", "/api/admin/users/7")).isEqualTo(AccessPolicy.Access.OWNER);
    }
}
