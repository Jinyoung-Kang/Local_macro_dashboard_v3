package com.macrodash.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserPaidTest {

    @Test
    @DisplayName("QA-043 사용자가 없는 요청은 유료 범위를 볼 수 없다 — 필터 설정이 어긋나도 열리지 않게(fail-closed)")
    void missingUserIsNotPaid() {
        assertThat(CurrentUser.paid(new MockHttpServletRequest())).isFalse();

        MockHttpServletRequest pro = new MockHttpServletRequest();
        pro.setAttribute(CurrentUser.ATTRIBUTE, new CurrentUser(1, "p@test.local", "MEMBER", "PRO", null));
        assertThat(CurrentUser.paid(pro)).isTrue();
        MockHttpServletRequest free = new MockHttpServletRequest();
        free.setAttribute(CurrentUser.ATTRIBUTE, new CurrentUser(2, "f@test.local", "MEMBER", "FREE", null));
        assertThat(CurrentUser.paid(free)).isFalse();
    }
}
