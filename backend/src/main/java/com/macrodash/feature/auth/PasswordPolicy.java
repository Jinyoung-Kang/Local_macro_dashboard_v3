package com.macrodash.feature.auth;

import java.util.Locale;
import java.util.Set;

/**
 * 새 비밀번호 규칙 — 가입·변경·재설정에 씁니다. 처음 운영자 계정은 지금의 APP_PASSWORD를 그대로 받습니다
 * (기존 사용 흐름 유지, 명세 B8). 문제가 없으면 null, 있으면 사람이 읽는 이유를 돌려줍니다.
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 200;

    /** 흔한 비밀번호 — 짧은 목록이지만 가장 먼저 대입되는 값들입니다. */
    static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "1234567890", "12345678910", "qwertyuiop", "qwerty1234",
            "1q2w3e4r5t", "1q2w3e4r!", "iloveyou12", "admin12345", "admin1234@", "abcd123456", "asdf123456",
            "zxcvbnm123", "111111111", "0000000000", "letmein123", "welcome123", "dashboard1");

    public static String problem(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "비밀번호는 %d자 이상이어야 합니다.".formatted(MIN_LENGTH);
        }
        if (password.length() > MAX_LENGTH) {
            return "비밀번호는 %d자 이하여야 합니다.".formatted(MAX_LENGTH);
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            return "너무 흔한 비밀번호입니다. 다른 비밀번호를 쓰세요.";
        }
        if (email != null && !email.isBlank()) {
            String local = email.toLowerCase(Locale.ROOT).split("@", 2)[0];
            if (lower.equals(email.toLowerCase(Locale.ROOT)) || (local.length() >= 4 && lower.contains(local))) {
                return "비밀번호에 이메일을 넣지 마세요.";
            }
        }
        if (password.chars().distinct().count() < 4) {
            return "같은 글자를 반복한 비밀번호는 쓸 수 없습니다.";
        }
        return null;
    }
}
