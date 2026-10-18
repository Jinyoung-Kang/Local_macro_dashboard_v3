package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.store.AuditLogStore;
import com.macrodash.store.SchemaCheck;
import com.macrodash.store.SessionStore;
import com.macrodash.store.UserStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증 테스트용 실제 테스트 DB(macrodash_test) 연결. 운영 DB로 돌지 않도록 이름을 확인하고,
 * 다른 테스트와 겹치지 않게 테스트마다 고유한 이메일 접두사({@code prefix})의 계정만 만들고 지웁니다.
 */
final class AuthTestDatabase {

    final JdbcTemplate jdbc;
    final UserStore users;
    final SessionStore sessions;
    final AuditLogStore audit;
    final PasswordHasher hasher = new PasswordHasher();
    final String prefix;

    AuthTestDatabase(String prefix) {
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL",
                "jdbc:postgresql://localhost:5432/macrodash_test");
        assertThat(url).as("테스트 DB만 씁니다").endsWith("_test");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DATABASE_USER", "macro"),
                System.getenv().getOrDefault("DATABASE_PASSWORD", "macro")));
        users = new UserStore(jdbc, new SchemaCheck(jdbc));
        sessions = new SessionStore(jdbc);
        audit = new AuditLogStore(jdbc, new ObjectMapper());
        this.prefix = prefix;
        cleanUp();
    }

    String email(String name) {
        return prefix + "-" + name + "@test.local";
    }

    long user(String name, String password, String role, String tier) {
        return users.insert(email(name), hasher.hash(password), role, tier, true);
    }

    AuthService service(AppProperties properties) {
        return new AuthService(properties, users, sessions, audit, hasher);
    }

    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", prefix + "-%");
        // 없는 계정의 로그인 실패 기록은 사용자 번호가 없어 위에서 지워지지 않습니다 — 이메일 접두사로 지웁니다.
        jdbc.update("DELETE FROM audit_log WHERE user_id IS NULL AND detail->>'email' LIKE ?", prefix + "-%");
    }
}
