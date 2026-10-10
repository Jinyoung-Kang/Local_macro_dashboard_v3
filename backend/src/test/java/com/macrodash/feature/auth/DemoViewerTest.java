package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.store.AuditLogStore;
import com.macrodash.store.SessionStore;
import com.macrodash.store.UserStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 데모 열람 토큰(ADR 0013) — 꺼짐이 기본, 짧은 토큰은 기동 실패, 정확히 같은 값만 통과. */
class DemoViewerTest {

    /** 테스트용 값(32자 이상). 실제 배포 토큰은 서버의 .env에만 있습니다. */
    static final String TOKEN = "test-demo-viewer-token-0123456789abcdef";

    @Test
    @DisplayName("토큰이 비면 꺼진다 — 어떤 헤더 값도 통과하지 않는다")
    void offByDefault() {
        for (String configured : new String[]{null, "", "   "}) {
            DemoViewer demo = DemoViewer.from(configured);
            assertThat(demo.enabled()).isFalse();
            assertThat(demo.matches(TOKEN)).isFalse();
            assertThat(demo.matches("")).isFalse();
            assertThat(demo.matches(null)).isFalse();
        }
    }

    @Test
    @DisplayName("켜져 있으면 정확히 같은 값만 통과한다(앞뒤 일부·대소문자·빈 값·없음은 거절)")
    void matchesExactTokenOnly() {
        DemoViewer demo = DemoViewer.from(TOKEN);
        assertThat(demo.enabled()).isTrue();
        assertThat(demo.matches(TOKEN)).isTrue();
        assertThat(demo.matches(TOKEN.substring(1))).isFalse();
        assertThat(demo.matches(TOKEN + "x")).isFalse();
        assertThat(demo.matches(TOKEN.toUpperCase())).isFalse();
        assertThat(demo.matches("")).isFalse();
        assertThat(demo.matches(null)).isFalse();
    }

    @Test
    @DisplayName("설정값 앞뒤 공백은 떼고 비교한다(.env 줄 끝 공백 — 헤더 값은 서버가 앞뒤 공백을 지움)")
    void configuredTokenIsStripped() {
        assertThat(DemoViewer.from("  " + TOKEN + " ").matches(TOKEN)).isTrue();
    }

    @Test
    @DisplayName("32자보다 짧은 토큰은 기동을 멈춘다 — 오류 문구에 값은 담지 않는다")
    void shortTokenFailsFast() {
        String weak = "short-demo-token-1234567890abcd";   // 31자
        assertThat(weak).hasSize(DemoViewer.MIN_TOKEN_LENGTH - 1);
        assertThatThrownBy(() -> DemoViewer.from(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHBOARD_DEMO_VIEWER_TOKEN")
                .hasMessageContaining("32자 이상")
                .hasMessageNotContaining(weak);
        assertThat(DemoViewer.from("a".repeat(DemoViewer.MIN_TOKEN_LENGTH)).enabled()).as("딱 32자는 허용").isTrue();
    }

    @Test
    @DisplayName("짧은 토큰이면 AuthService(기동 때 만드는 빈)를 만들 수 없다 → 애플리케이션이 뜨지 않는다")
    void authServiceRefusesShortToken() {
        AppProperties properties = new AppProperties();
        properties.setDemoViewerToken("too-short");
        assertThatThrownBy(() -> new AuthService(properties, mock(UserStore.class), mock(SessionStore.class),
                mock(AuditLogStore.class), new PasswordHasher()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHBOARD_DEMO_VIEWER_TOKEN");
    }

    @Test
    @DisplayName("데모 열람 사용자: 일반 회원·유료 등급, 계정·이메일·세션 없음, 실제 계정과 겹치지 않는 번호")
    void syntheticUser() {
        CurrentUser user = DemoViewer.user();
        assertThat(user.demo()).isTrue();
        assertThat(user.id()).isNegative();
        assertThat(user.role()).isEqualTo("MEMBER");
        assertThat(user.tier()).isEqualTo("PRO");
        assertThat(user.isOwner()).isFalse();
        assertThat(user.isPro()).isTrue();
        assertThat(user.email()).isNull();
        assertThat(user.sessionId()).isNull();
        assertThat(new CurrentUser(1, "a@test.local", "MEMBER", "FREE", null).demo())
                .as("계정 사용자는 데모가 아님").isFalse();
    }
}
