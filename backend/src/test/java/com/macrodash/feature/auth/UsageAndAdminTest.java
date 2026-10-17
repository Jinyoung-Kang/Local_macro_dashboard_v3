package com.macrodash.feature.auth;

import com.macrodash.store.UsageStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루 사용량 한도(AI 리포트)와 운영자의 사용자 관리 — 실제 테스트 DB.
 */
class UsageAndAdminTest {

    private final AuthTestDatabase db = new AuthTestDatabase("usage");

    @AfterEach
    void cleanUp() {
        db.cleanUp();
    }

    @Test
    @DisplayName("유료 회원은 하루 한도까지, 동시에 눌러도 한도를 넘지 않는다. 무료는 0건, 운영자는 한도 없음")
    void dailyLimit() throws Exception {
        UsageService usage = new UsageService(new UsageStore(db.jdbc), 3, 20);
        long proId = db.user("pro", "pro passphrase!!", "MEMBER", "PRO");
        CurrentUser pro = new CurrentUser(proId, db.email("pro"), "MEMBER", "PRO", null);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Callable<Boolean> call = () -> usage.consume(pro, UsageService.AI_REPORT).allowed();
            long allowed = 0;
            for (Future<Boolean> result : pool.invokeAll(List.of(call, call, call, call, call, call))) {
                allowed += result.get() ? 1 : 0;
            }
            assertThat(allowed).isEqualTo(3);
        } finally {
            pool.shutdown();
        }
        usage.refund(pro, UsageService.AI_REPORT);
        assertThat(usage.consume(pro, UsageService.AI_REPORT).allowed()).as("실패한 호출은 되돌려 다시 씀").isTrue();

        long freeId = db.user("free", "free passphrase!", "MEMBER", "FREE");
        assertThat(usage.consume(new CurrentUser(freeId, db.email("free"), "MEMBER", "FREE", null),
                UsageService.AI_REPORT).allowed()).isFalse();
        long ownerId = db.user("own", "owner passphrase", "OWNER", "FREE");
        CurrentUser owner = new CurrentUser(ownerId, db.email("own"), "OWNER", "FREE", null);
        for (int i = 0; i < 5; i++) {
            assertThat(usage.consume(owner, UsageService.AI_REPORT).allowed()).isTrue();
        }
        assertThat(usage.consume(owner, UsageService.AI_REPORT).limit()).isNull();
    }

    @Test
    @DisplayName("운영자: 회원 만들기·등급 바꾸기, 자기 운영자 역할은 못 내림, 감사 기록이 남는다")
    void adminActions() {
        long ownerId = db.user("admin", "owner passphrase", "OWNER", "PRO");
        CurrentUser owner = new CurrentUser(ownerId, db.email("admin"), "OWNER", "PRO", null);
        AdminService admin = new AdminService(db.users, db.audit, db.hasher, new com.macrodash.store.AlertStore(
                db.jdbc, new tools.jackson.databind.ObjectMapper(), new com.macrodash.store.SchemaCheck(db.jdbc)));

        assertThat(admin.create(owner, db.email("m1"), "member passphrase", "FREE")).isNull();
        assertThat(admin.create(owner, db.email("m1"), "member passphrase", "FREE")).contains("이미");
        long memberId = db.users.findByEmail(db.email("m1")).orElseThrow().id();
        assertThat(db.users.findById(memberId).orElseThrow().emailVerifiedAt()).as("운영자가 만든 회원은 인증됨").isNotNull();

        assertThat(admin.update(owner, memberId, "MEMBER", "PRO")).isNull();
        assertThat(db.users.findById(memberId).orElseThrow().tier()).isEqualTo("PRO");
        assertThat(admin.update(owner, ownerId, "MEMBER", "PRO")).contains("자기 자신");
        assertThat(admin.update(owner, memberId, "GOD", "PRO")).contains("역할");
        assertThat(admin.audit(50)).anySatisfy(row -> assertThat(row.get("action")).isEqualTo("user.updated"));
    }

    @Test
    @DisplayName("QA-115 이미 무료인 회원을 무료로 다시 저장해도 켜진 유료 알림 규칙을 끈다 — 여러 번 저장해도 같음")
    void savingUnpaidUserDisablesPaidRulesEvenIfAlreadyUnpaid() {
        long ownerId = db.user("admin", "owner passphrase", "OWNER", "PRO");
        CurrentUser owner = new CurrentUser(ownerId, db.email("admin"), "OWNER", "PRO", null);
        AdminService admin = new AdminService(db.users, db.audit, db.hasher, new com.macrodash.store.AlertStore(
                db.jdbc, new tools.jackson.databind.ObjectMapper(), new com.macrodash.store.SchemaCheck(db.jdbc)));
        // 이 배포 전에 PRO → FREE로 내려(그때는 규칙을 끄지 않았음) 유료 규칙이 켜진 채 남은 회원.
        long memberId = db.user("m2", "member passphrase", "MEMBER", "FREE");
        db.jdbc.update("INSERT INTO alert_rules (user_id, kind, params) VALUES (?, 'REGIME_CHANGE', '{}'::jsonb), "
                + "(?, 'INSTITUTION_NEW', '{\"cik\":\"0001067983\"}'::jsonb)", memberId, memberId);
        String lastDisabled = "SELECT detail->>'disabledAlertRules' FROM audit_log WHERE action = 'user.updated' "
                + "AND detail->>'target' = ? ORDER BY id DESC LIMIT 1";

        assertThat(admin.update(owner, memberId, "MEMBER", "FREE")).isNull();
        assertThat(db.jdbc.queryForList("SELECT kind, enabled FROM alert_rules WHERE user_id = ? ORDER BY id", memberId))
                .extracting(row -> row.get("kind") + ":" + row.get("enabled"))
                .containsExactly("REGIME_CHANGE:true", "INSTITUTION_NEW:false");
        assertThat(db.jdbc.queryForObject(lastDisabled, String.class, String.valueOf(memberId)))
                .as("감사 기록에 끈 규칙 수").isEqualTo("1");

        assertThat(admin.update(owner, memberId, "MEMBER", "FREE")).isNull();
        assertThat(db.jdbc.queryForObject(lastDisabled, String.class, String.valueOf(memberId)))
                .as("다시 저장하면 이미 꺼져 있어 0").isEqualTo("0");
    }
}
