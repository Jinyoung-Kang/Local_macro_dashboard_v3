package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 계정({@code users}) — 백엔드가 쓰는 테이블(ADR 0008·0010).
 *
 * <p>이메일은 대소문자를 가리지 않고 하나뿐입니다({@code lower(email)} 유일 인덱스). 비밀번호는 해시만 둡니다.
 */
@Repository
public class UserStore {

    /** 계정 한 건. {@code passwordHash}는 Argon2id 해시 문자열입니다. */
    public record User(long id, String email, String passwordHash, String role, String tier,
                       Instant emailVerifiedAt, Instant createdAt) {
    }

    private static final String COLUMNS =
            "id, email, password_hash, role, tier, email_verified_at, created_at";

    private final JdbcTemplate jdbc;
    private final SchemaCheck schema;
    /** 같은 DataSource의 트랜잭션 — 세션·감사 기록 저장소의 JdbcTemplate도 이 연결에 합류합니다. */
    private final TransactionTemplate transactions;

    public UserStore(JdbcTemplate jdbc, SchemaCheck schema) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /**
     * 계정에 걸친 쓰기 여러 개를 한 트랜잭션으로 묶습니다(QA-128) — 안에서 예외가 나면 모두 되돌리고 그 예외를 그대로 던집니다.
     * 같은 DataSource를 쓰는 다른 저장소(세션·감사 기록)의 쓰기도 이 스레드의 같은 연결에 합류합니다.
     *
     * <p>예전에는 문장마다 자동 커밋이라, 비밀번호 변경 중 DB 오류가 나면 비밀번호는 바뀌었는데 다른 기기 세션은 살아 있거나,
     * 탈퇴 중 삭제가 실패하면 탈퇴 감사 기록만 남았습니다. Argon2 해시처럼 오래 걸리는 계산은 부르기 전에 끝내 둡니다
     * (그동안 연결을 쥐지 않게 — AWS 연결 풀은 4개).
     */
    public <T> T atomically(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    /** 계정 테이블이 있는가(수집기가 V4를 적용한 뒤). */
    public boolean ready() {
        return schema.tableExists("users");
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count == null ? 0 : count;
    }

    public Optional<User> findByEmail(String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE lower(email) = lower(?)", mapper(), email)
                .stream().findFirst();
    }

    public Optional<User> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = ?", mapper(), id).stream().findFirst();
    }

    public List<User> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM users ORDER BY id", mapper());
    }

    /**
     * 계정을 만듭니다.
     *
     * @return 새 계정 번호
     * @throws org.springframework.dao.DuplicateKeyException 같은 이메일이 이미 있으면
     */
    public long insert(String email, String passwordHash, String role, String tier, boolean verified) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (email, password_hash, role, tier, email_verified_at) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, email);
            ps.setString(2, passwordHash);
            ps.setString(3, role);
            ps.setString(4, tier);
            ps.setTimestamp(5, verified ? Timestamp.from(Instant.now()) : null);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("새 계정 번호를 받지 못했습니다");
        }
        return id.longValue();
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?", passwordHash, id);
    }

    public void updateRoleAndTier(long id, String role, String tier) {
        jdbc.update("UPDATE users SET role = ?, tier = ?, updated_at = now() WHERE id = ?", role, tier, id);
    }

    public void markVerified(long id) {
        jdbc.update("UPDATE users SET email_verified_at = COALESCE(email_verified_at, now()), updated_at = now() "
                + "WHERE id = ?", id);
    }

    /** 계정을 지웁니다. 세션은 함께 지워지고(외래키 CASCADE), 감사 기록은 사용자 번호만 비워집니다. */
    public void delete(long id) {
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    private static RowMapper<User> mapper() {
        return (rs, rowNum) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("role"), rs.getString("tier"),
                instant(rs.getTimestamp("email_verified_at")), instant(rs.getTimestamp("created_at")));
    }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
