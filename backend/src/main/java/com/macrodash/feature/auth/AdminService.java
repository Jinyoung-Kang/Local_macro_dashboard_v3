package com.macrodash.feature.auth;

import com.macrodash.store.AlertStore;
import com.macrodash.store.AuditLogStore;
import com.macrodash.store.UserStore;
import com.macrodash.support.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 운영자의 사용자 관리 — 목록, 역할·등급 지정, 회원 만들기, 감사 기록(명세 B5·B9).
 *
 * <p>결제 연동 전까지 등급은 여기서 수동으로 정합니다(D4). 세션은 요청마다 사용자 행을 다시 읽으므로, 바꾼 역할·등급은
 * 그 사용자의 다음 요청부터 바로 반영됩니다. 운영자가 자기 역할을 내려 아무도 관리하지 못하게 되는 일은 막습니다.
 */
@Service
public class AdminService {

    static final Set<String> ROLES = Set.of("OWNER", "MEMBER");
    static final Set<String> TIERS = Set.of("FREE", "PRO");

    private final UserStore users;
    private final AuditLogStore audit;
    private final PasswordHasher hasher;
    private final AlertStore alerts;

    public AdminService(UserStore users, AuditLogStore audit, PasswordHasher hasher, AlertStore alerts) {
        this.users = users;
        this.audit = audit;
        this.hasher = hasher;
        this.alerts = alerts;
    }

    public List<Map<String, Object>> users() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserStore.User user : users.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.id());
            row.put("email", user.email());
            row.put("role", user.role());
            row.put("tier", user.tier());
            row.put("verified", user.emailVerifiedAt() != null);
            row.put("createdAt", String.valueOf(user.createdAt()));
            out.add(row);
        }
        return out;
    }

    /** @return 문제가 있으면 이유, 성공이면 null */
    public String update(CurrentUser admin, long userId, String role, String tier) {
        if (!ROLES.contains(role) || !TIERS.contains(tier)) {
            return "역할은 OWNER·MEMBER, 등급은 FREE·PRO 중 하나여야 합니다.";
        }
        UserStore.User target = users.findById(userId).orElse(null);
        if (target == null) {
            return "없는 사용자입니다.";
        }
        if (target.id() == admin.id() && !"OWNER".equals(role)) {
            return "자기 자신의 운영자 역할은 내릴 수 없습니다(운영자가 없어지면 아무도 관리할 수 없습니다).";
        }
        users.updateRoleAndTier(userId, role, tier);
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("target", userId, "role", role, "tier", tier,
                "fromRole", target.role(), "fromTier", target.tier()));
        // 유료에서 무료로 내리면 국면 전환 밖의 알림 규칙을 끕니다(명세 B5 — 등급 변경은 다음 요청부터 반영). 끄지 않으면
        // 정기 평가가 유료 알림을 계속 넣고, 관심 종목이 종목별 수급 수집 범위(전체 60)를 계속 차지했습니다(QA-091).
        // 다시 유료로 올려도 저절로 켜지 않습니다 — 회원이 알림 화면에서 켭니다.
        if (paid(target.role(), target.tier()) && !paid(role, tier) && alerts.ready()) {
            detail.put("disabledAlertRules", alerts.disablePaidRules(userId));
        }
        audit.append(admin.id(), "user.updated", detail);
        return null;
    }

    /** 유료 기능을 쓸 수 있는 역할·등급인가 — {@link CurrentUser#isPro()}와 같은 규칙(운영자는 등급과 무관). */
    private static boolean paid(String role, String tier) {
        return "OWNER".equals(role) || "PRO".equals(tier);
    }

    /** 메일 없이 회원을 만듭니다(인증된 상태). 처음 비밀번호는 운영자가 정해 전달하고, 회원이 내 계정에서 바꿉니다. */
    public String create(CurrentUser admin, String email, String password, String tier) {
        String normalized = AuthService.normalizeEmail(email);
        if (normalized == null) {
            return "메일 주소 형식이 아닙니다.";
        }
        if (!TIERS.contains(tier)) {
            return "등급은 FREE·PRO 중 하나여야 합니다.";
        }
        String problem = PasswordPolicy.problem(password, normalized);
        if (problem != null) {
            return problem;
        }
        try {
            long id = users.insert(normalized, hasher.hash(password), "MEMBER", tier, true);
            audit.append(admin.id(), "user.created", Map.of("target", id, "tier", tier));
            return null;
        } catch (DuplicateKeyException e) {
            return "이미 있는 메일 주소입니다.";
        }
    }

    public List<Map<String, Object>> audit(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLogStore.Entry entry : this.audit.recent(Math.max(1, Math.min(limit, 500)))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", String.valueOf(entry.at()));
            row.put("userId", entry.userId());
            row.put("action", entry.action());
            row.put("detail", entry.detail());
            out.add(row);
        }
        return out;
    }
}
