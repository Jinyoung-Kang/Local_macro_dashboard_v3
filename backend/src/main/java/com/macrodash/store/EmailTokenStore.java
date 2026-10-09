package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 메일 링크 토큰({@code email_tokens}) — 가입 인증·비밀번호 재설정. 원문 대신 SHA-256만 둡니다(ADR 0010).
 */
@Repository
public class EmailTokenStore {

    private final JdbcTemplate jdbc;

    public EmailTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(byte[] tokenHash, long userId, String purpose, Instant expiresAt) {
        jdbc.update("INSERT INTO email_tokens (token_hash, user_id, purpose, expires_at) VALUES (?, ?, ?, ?)",
                tokenHash, userId, purpose, Timestamp.from(expiresAt));
    }

    /**
     * 유효한 토큰을 <b>한 번만</b> 씁니다 — 아직 쓰지 않았고 만료 전이면 used_at을 찍고 사용자 번호를 돌려줍니다.
     * 동시에 두 번 눌러도 한 번만 성공합니다(조건부 UPDATE).
     */
    public Optional<Long> consume(byte[] tokenHash, String purpose) {
        return jdbc.query("UPDATE email_tokens SET used_at = now() "
                        + "WHERE token_hash = ? AND purpose = ? AND used_at IS NULL AND expires_at > now() "
                        + "RETURNING user_id",
                (rs, rowNum) -> rs.getLong("user_id"), tokenHash, purpose).stream().findFirst();
    }

    /** 그 사용자의 아직 쓰지 않은 같은 용도 토큰을 모두 무효로(새 링크를 보내면 이전 링크는 못 씀). */
    public void invalidate(long userId, String purpose) {
        jdbc.update("UPDATE email_tokens SET used_at = now() WHERE user_id = ? AND purpose = ? AND used_at IS NULL",
                userId, purpose);
    }
}
