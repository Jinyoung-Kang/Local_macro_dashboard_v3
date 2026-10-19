package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.store.SchemaCheck;
import com.macrodash.store.UserStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 무로그인 운영자(ADR 0016) — 실제 테스트 DB로 {@link UserStore#firstOwner}와 {@link AuthService#resolve}의 순서
 * (유효한 쿠키 → 데모 헤더 → 첫 운영자)를 확인합니다.
 *
 * <p>테스트 DB에는 다른 테스트가 만든 운영자가 남아 있을 수 있어, "첫 운영자가 누구인가"를 정확히 봐야 하는 시험은
 * 연결 하나에만 보이는 임시 {@code users} 테이블({@link Isolated})에서 합니다 — 남의 계정을 건드리지 않습니다.
 */
class LocalOwnerResolveTest {

    private final AuthTestDatabase db = new AuthTestDatabase("authlocal");

    @AfterEach
    void cleanUp() {
        db.cleanUp();
    }

    private static AppProperties noLogin(boolean on) {
        AppProperties properties = new AppProperties();
        properties.setLocalNoLogin(on);
        return properties;
    }

    @Test
    @DisplayName("켜져 있고 이 맥 요청이면 쿠키 없이 첫 운영자 — 세션 없음(sessionId null)·데모 아님, 세션 행도 만들지 않는다")
    void localRequestBecomesFirstOwner() {
        long mine = db.user("owner", "correct horse battery", "OWNER", "PRO");
        UserStore.User first = db.users.firstOwner().orElseThrow();
        assertThat(first.role()).isEqualTo("OWNER");
        assertThat(first.id()).as("가장 먼저 만든 운영자(이 테스트의 운영자보다 늦을 수 없음)").isLessThanOrEqualTo(mine);
        Integer sessionsBefore = db.jdbc.queryForObject("SELECT count(*) FROM user_sessions", Integer.class);

        CurrentUser user = db.service(noLogin(true)).resolve(null, null, true).orElseThrow();

        assertThat(user.id()).isEqualTo(first.id());
        assertThat(user.email()).isEqualTo(first.email());
        assertThat(user.role()).isEqualTo("OWNER");
        assertThat(user.tier()).isEqualTo(first.tier());
        assertThat(user.sessionId()).isNull();
        assertThat(user.demo()).isFalse();
        assertThat(user.isLocalOwner()).isTrue();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM user_sessions", Integer.class))
                .as("요청마다 세션 행을 만들지 않음").isEqualTo(sessionsBefore);
    }

    @Test
    @DisplayName("유효한 세션 쿠키가 먼저다 — 켜져 있고 이 맥 요청이어도 쿠키의 계정(무료 회원)으로")
    void cookieWins() {
        db.user("owner", "correct horse battery", "OWNER", "PRO");
        long memberId = db.user("member", "correct horse battery", "MEMBER", "FREE");
        AuthService auth = db.service(noLogin(true));
        String token = auth.openSession(db.users.findById(memberId).orElseThrow(), "JUnit").token();

        CurrentUser user = auth.resolve(token, null, true).orElseThrow();
        assertThat(user.id()).isEqualTo(memberId);
        assertThat(user.role()).isEqualTo("MEMBER");
        assertThat(user.sessionId()).isNotNull();
        assertThat(user.isLocalOwner()).isFalse();

        CurrentUser expired = auth.resolve("not-a-session", null, true).orElseThrow();
        assertThat(expired.isLocalOwner()).as("틀린 쿠키는 쿠키가 없는 것과 같음 → 첫 운영자").isTrue();
    }

    @Test
    @DisplayName("이 맥 요청이 아니거나(앞단·데모 헤더가 붙으면 부르는 쪽이 false) 기능이 꺼져 있으면 비어 있다 — 평소처럼 401")
    void nonLocalOrOffIsEmpty() {
        db.user("owner", "correct horse battery", "OWNER", "PRO");
        assertThat(db.service(noLogin(true)).resolve(null, null, false)).isEmpty();
        assertThat(db.service(noLogin(true)).resolve(null, DemoViewerTest.TOKEN, false))
                .as("데모 열람이 꺼진 로컬에서 데모 헤더").isEmpty();
        assertThat(db.service(noLogin(false)).resolve(null, null, true)).as("기능 꺼짐(기본)").isEmpty();
        assertThat(db.service(noLogin(false)).localNoLogin()).isFalse();
        assertThat(db.service(noLogin(true)).localNoLogin()).isTrue();
    }

    @Test
    @DisplayName("운영자 계정이 없으면 비어 있다(계정 만들기 확인을 한 번 더 해도 OWNER_EMAIL이 없으면 못 만듦)")
    void noOwnerIsEmpty() {
        try (Isolated isolated = new Isolated()) {
            assertThat(isolated.users.firstOwner()).isEmpty();
            AuthService auth = isolated.service(noLogin(true));
            assertThat(auth.resolve(null, null, true)).isEmpty();

            isolated.users.insert("authlocal-member@test.local", "x", "MEMBER", "PRO", true);
            assertThat(isolated.users.firstOwner()).as("회원만 있으면 운영자 없음").isEmpty();
            assertThat(auth.resolve(null, null, true)).isEmpty();
        }
    }

    @Test
    @DisplayName("운영자가 없는 동안 요청 경로의 계정 만들기 재확인은 1분에 한 번만 — 화면이 5초마다 물어도 잠금·경고가 쌓이지 않는다")
    void missingOwnerRetryIsRateLimited() {
        try (Isolated isolated = new Isolated()) {
            AtomicInteger retries = new AtomicInteger();
            AuthService auth = new AuthService(noLogin(true), isolated.users,
                    org.mockito.Mockito.mock(com.macrodash.store.SessionStore.class),
                    org.mockito.Mockito.mock(com.macrodash.store.AuditLogStore.class), new PasswordHasher()) {
                @Override
                public void retryOwner() {
                    retries.incrementAndGet();
                    super.retryOwner();
                }
            };
            for (int i = 0; i < 5; i++) {
                assertThat(auth.resolve(null, null, true)).isEmpty();
            }
            assertThat(retries.get()).as("요청 다섯 번에 재확인 한 번").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("운영자가 없을 때 계정이 하나도 없고 OWNER_EMAIL·APP_PASSWORD가 있으면 그 자리에서 운영자를 만들어 연다")
    void missingOwnerIsCreatedOnce() {
        try (Isolated isolated = new Isolated()) {
            AppProperties properties = noLogin(true);
            properties.setOwnerEmail("authlocal-bootstrap@test.local");
            properties.setPassword("bootstrap-password-1");
            CurrentUser user = isolated.service(properties).resolve(null, null, true).orElseThrow();
            assertThat(user.email()).isEqualTo("authlocal-bootstrap@test.local");
            assertThat(user.isLocalOwner()).isTrue();
        }
    }

    @Test
    @DisplayName("첫 운영자는 id가 가장 작은 OWNER — 회원으로 내리면 다음 요청부터 그다음 운영자(캐시 없음)")
    void demotingFirstOwnerMovesToNext() {
        try (Isolated isolated = new Isolated()) {
            UserStore users = isolated.users;
            long member = users.insert("authlocal-m@test.local", "x", "MEMBER", "PRO", true);
            long first = users.insert("authlocal-a@test.local", "x", "OWNER", "FREE", true);
            long second = users.insert("authlocal-b@test.local", "x", "OWNER", "PRO", true);
            assertThat(member).isLessThan(first);
            AuthService auth = isolated.service(noLogin(true));

            assertThat(users.firstOwner().map(UserStore.User::id)).contains(first);
            CurrentUser before = auth.resolve(null, null, true).orElseThrow();
            assertThat(before.id()).isEqualTo(first);
            assertThat(before.tier()).as("등급은 계정 그대로").isEqualTo("FREE");

            users.updateRoleAndTier(first, "MEMBER", "FREE");
            assertThat(users.firstOwner().map(UserStore.User::id)).contains(second);
            assertThat(auth.resolve(null, null, true).map(CurrentUser::id)).contains(second);
        }
    }

    /**
     * 연결 하나에만 보이는 빈 {@code users}(임시 테이블 — 같은 이름의 실제 테이블보다 먼저 찾아짐). 닫으면 연결과 함께
     * 사라집니다. 실제 테이블의 기본값·제약·인덱스를 그대로 가져와 같은 SQL이 돕니다(id는 실제 테이블의 시퀀스).
     */
    private static final class Isolated implements AutoCloseable {
        final SingleConnectionDataSource dataSource;
        final JdbcTemplate jdbc;
        final UserStore users;

        Isolated() {
            String url = System.getenv().getOrDefault("TEST_DATABASE_URL",
                    "jdbc:postgresql://localhost:5432/macrodash_test");
            assertThat(url).as("테스트 DB만 씁니다").endsWith("_test");
            dataSource = new SingleConnectionDataSource(url, System.getenv().getOrDefault("DATABASE_USER", "macro"),
                    System.getenv().getOrDefault("DATABASE_PASSWORD", "macro"), true);
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TEMP TABLE users (LIKE public.users INCLUDING ALL)");
            users = new UserStore(jdbc, new SchemaCheck(jdbc));
        }

        AuthService service(AppProperties properties) {
            // 세션·감사 기록은 쓰지 않는 시험만 합니다(실제 테이블의 FK는 임시 users를 모름).
            return new AuthService(properties, users, org.mockito.Mockito.mock(com.macrodash.store.SessionStore.class),
                    org.mockito.Mockito.mock(com.macrodash.store.AuditLogStore.class), new PasswordHasher());
        }

        @Override
        public void close() {
            jdbc.execute("DROP TABLE IF EXISTS pg_temp.users");
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("임시 users는 이 연결에만 보인다 — 실제 테이블은 그대로")
    void isolatedTableDoesNotTouchRealUsers() {
        long realBefore = db.users.count();
        try (Isolated isolated = new Isolated()) {
            isolated.users.insert("authlocal-only-temp@test.local", "x", "OWNER", "PRO", true);
            assertThat(isolated.users.count()).isEqualTo(1);
            assertThat(db.users.findByEmail("authlocal-only-temp@test.local")).isEqualTo(Optional.empty());
        }
        assertThat(db.users.count()).isEqualTo(realBefore);
    }
}
