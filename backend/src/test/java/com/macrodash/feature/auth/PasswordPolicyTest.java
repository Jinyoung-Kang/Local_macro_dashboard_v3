package com.macrodash.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    @DisplayName("짧은·흔한·이메일을 담은·같은 글자 반복 비밀번호는 거절, 긴 문장은 통과")
    void rules() {
        assertThat(PasswordPolicy.problem("short", null)).contains("10자");
        assertThat(PasswordPolicy.problem("Password123", null)).contains("흔한");
        assertThat(PasswordPolicy.problem("jinyoung-is-me", "jinyoung@example.com")).contains("이메일");
        assertThat(PasswordPolicy.problem("aaaaaaaaaaaa", null)).contains("반복");
        assertThat(PasswordPolicy.problem("correct horse battery staple", "me@example.com")).isNull();
    }
}
