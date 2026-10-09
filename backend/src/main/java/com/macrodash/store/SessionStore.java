package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 로그인 세션({@code user_sessions}) — 쿠키 토큰의 SHA-256만 둡니다(ADR 0010).
 *
 * <p>DB에 두는 이유: 로그아웃·비밀번호 변경·등급 변경·계정 삭제가 <b>다음 요청부터</b> 반영되고, 재시작해도
 * 유지돼야 합니다. 서명 토큰(JWT)은 이를 위해 결국 서버에 차단 목록을 둬야 하고, 그 목록이 메모리에 있으면
 * 재시작 때 사라집니다(예전 구조의 한계).
 */
@Repository
public class SessionStore {

    /** 유효한 세션과 그 사용자. */
    public record Active(UUID sessionId, long userId, String email, String role, String tier,
                         Instant lastSeenAt, Instant expiresAt) {
    }

    /** 세션 목록의 한 줄(토큰 해시는 내보내지 않음). */
    public record Listed(UUID sessionId, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
                        String userAgent) {
    }

    private final JdbcTemplate jdbc;

    public SessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(long userId, byte[] tokenHash, Instant expiresAt, String userAgent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_sessions (id, user_id, token_hash, expires_at, user_agent) VALUES (?, ?, ?, ?, ?)",
                id, userId, tokenHash, Timestamp.from(expiresAt), userAgent);
        return id;
    }

    /** 해지되지 않았고 만료되지 않은 세션. */
    public Optional<Active> findActive(byte[] tokenHash) {
        return jdbc.query("SELECT s.id, s.user_id, u.email, u.role, u.tier, s.last_seen_at, s.expires_at "
                        + "FROM user_sessions s JOIN users u ON u.id = s.user_id "
                        + "WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now()",
                (rs, rowNum) -> new Active(rs.getObject("id", UUID.class), rs.getLong("user_id"),
                        rs.getString("email"), rs.getString("role"), rs.getString("tier"),
                        UserStore.instant(rs.getTimestamp("last_seen_at")),
                        UserStore.instant(rs.getTimestamp("expires_at"))),
                (Object) tokenHash).stream().findFirst();
    }

    /** 마지막 사용 시각 — 요청마다 쓰지 않도록 {@code minGap}보다 오래됐을 때만 갱신합니다. */
    public void touch(UUID sessionId, Duration minGap) {
        jdbc.update("UPDATE user_sessions SET last_seen_at = now() "
                + "WHERE id = ? AND last_seen_at < now() - make_interval(secs => ?)", sessionId, minGap.toSeconds());
    }

    public void revoke(UUID sessionId) {
        jdbc.update("UPDATE user_sessions SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", sessionId);
    }

    /** 그 사용자의 세션을 모두 끊습니다. {@code except}가 있으면 그 세션은 남깁니다(지금 쓰는 기기). */
    public int revokeAllForUser(long userId, UUID except) {
        return except == null
                ? jdbc.update("UPDATE user_sessions SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL",
                        userId)
                : jdbc.update("UPDATE user_sessions SET revoked_at = now() "
                        + "WHERE user_id = ? AND revoked_at IS NULL AND id <> ?", userId, except);
    }

    public List<Listed> activeForUser(long userId) {
        return jdbc.query("SELECT id, created_at, last_seen_at, expires_at, user_agent FROM user_sessions "
                        + "WHERE user_id = ? AND revoked_at IS NULL AND expires_at > now() ORDER BY last_seen_at DESC",
                (rs, rowNum) -> new Listed(rs.getObject("id", UUID.class),
                        UserStore.instant(rs.getTimestamp("created_at")),
                        UserStore.instant(rs.getTimestamp("last_seen_at")),
                        UserStore.instant(rs.getTimestamp("expires_at")), rs.getString("user_agent")),
                userId);
    }

    /** 해지·만료된 지 오래된 세션 정리. */
    public int purgeOlderThan(Duration age) {
        return jdbc.update("DELETE FROM user_sessions WHERE (revoked_at IS NOT NULL OR expires_at < now()) "
                + "AND expires_at < now() - make_interval(secs => ?)", age.toSeconds());
    }
}
