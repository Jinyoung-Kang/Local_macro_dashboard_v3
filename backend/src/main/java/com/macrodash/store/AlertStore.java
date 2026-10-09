package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 알림 규칙·사건·알림함({@code alert_rules}·{@code alert_events}·{@code alert_deliveries}) — 백엔드가 쓰는 테이블
 * (ADR 0008·0011). 사건 키와 (사건, 사용자, 채널)이 유일해서, 같은 데이터를 몇 번 평가해도 결과는 한 번입니다.
 */
@Repository
public class AlertStore {

    public record Rule(long id, long userId, String kind, JsonNode params, boolean enabled, Instant createdAt) {
    }

    public record Event(long id, String kind, String eventKey, LocalDate occurredOn, String title, String body) {
    }

    public record InboxItem(long id, String kind, LocalDate occurredOn, String title, String body, boolean late,
                            Instant createdAt, Instant readAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SchemaCheck schema;

    public AlertStore(JdbcTemplate jdbc, ObjectMapper mapper, SchemaCheck schema) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.schema = schema;
    }

    public boolean ready() {
        return schema.tableExists("alert_deliveries");
    }

    // ------------------------------------------------------------------ 규칙
    public List<Rule> rules(long userId) {
        return jdbc.query("SELECT id, user_id, kind, params::text AS params, enabled, created_at FROM alert_rules "
                + "WHERE user_id = ? ORDER BY id", ruleMapper(), userId);
    }

    public List<Rule> enabledRules(String kind) {
        return jdbc.query("SELECT id, user_id, kind, params::text AS params, enabled, created_at FROM alert_rules "
                + "WHERE kind = ? AND enabled ORDER BY id", ruleMapper(), kind);
    }

    public int countRules(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_rules WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 종목 대상 연속 순매수 규칙의 서로 다른 종목 수(전체 또는 한 사용자). 종목별 수급 수집 범위의 상한을 지킵니다. */
    public int countStockCodes(Long userId) {
        String sql = "SELECT COUNT(DISTINCT params->>'code') FROM alert_rules "
                + "WHERE kind = 'FOREIGN_STREAK' AND params->>'target' = 'STOCK' AND enabled"
                + (userId == null ? "" : " AND user_id = ?");
        Integer count = userId == null ? jdbc.queryForObject(sql, Integer.class)
                : jdbc.queryForObject(sql, Integer.class, userId);
        return count == null ? 0 : count;
    }

    public boolean hasStockCode(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_rules WHERE kind = 'FOREIGN_STREAK' "
                + "AND params->>'target' = 'STOCK' AND params->>'code' = ? AND enabled", Integer.class, code);
        return count != null && count > 0;
    }

    public long insertRule(long userId, String kind, JsonNode params) {
        Long id = jdbc.queryForObject("INSERT INTO alert_rules (user_id, kind, params) VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, userId, kind, mapper.writeValueAsString(params));
        if (id == null) {
            throw new IllegalStateException("규칙 번호를 받지 못했습니다");
        }
        return id;
    }

    public int setEnabled(long ruleId, long userId, boolean enabled) {
        return jdbc.update("UPDATE alert_rules SET enabled = ? WHERE id = ? AND user_id = ?", enabled, ruleId, userId);
    }

    public int deleteRule(long ruleId, long userId) {
        return jdbc.update("DELETE FROM alert_rules WHERE id = ? AND user_id = ?", ruleId, userId);
    }

    // ------------------------------------------------------------------ 사건
    /** 사건을 넣습니다. 같은 키가 이미 있으면 넣지 않고 비어 있음. */
    public Optional<Long> insertEvent(String kind, String eventKey, LocalDate occurredOn, String title, String body,
                                      JsonNode payload) {
        return jdbc.query("INSERT INTO alert_events (kind, event_key, occurred_on, title, body, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (event_key) DO NOTHING RETURNING id",
                (rs, rowNum) -> rs.getLong("id"), kind, eventKey, Date.valueOf(occurredOn), title, body,
                mapper.writeValueAsString(payload)).stream().findFirst();
    }

    /** 최근에 만든 사건 — 정기 평가가 규칙과 다시 맞춰 봅니다(규칙을 나중에 만든 경우). */
    public List<Event> recentEvents(String kind, int days) {
        return jdbc.query("SELECT id, kind, event_key, occurred_on, title, body FROM alert_events "
                        + "WHERE kind = ? AND created_at > now() - make_interval(days => ?) ORDER BY id",
                (rs, rowNum) -> new Event(rs.getLong("id"), rs.getString("kind"), rs.getString("event_key"),
                        rs.getDate("occurred_on").toLocalDate(), rs.getString("title"), rs.getString("body")),
                kind, days);
    }

    /** 알림함에 넣습니다. 이미 있으면 그대로(유일 제약). @return 새로 넣었으면 true */
    public boolean deliver(long eventId, long userId, long ruleId, boolean late) {
        return jdbc.update("INSERT INTO alert_deliveries (event_id, user_id, rule_id, channel, late) "
                + "VALUES (?, ?, ?, 'inbox', ?) ON CONFLICT (event_id, user_id, channel) DO NOTHING",
                eventId, userId, ruleId, late) == 1;
    }

    // ------------------------------------------------------------------ 알림함
    public List<InboxItem> inbox(long userId, int limit) {
        return jdbc.query("SELECT d.id, e.kind, e.occurred_on, e.title, e.body, d.late, d.created_at, d.read_at "
                        + "FROM alert_deliveries d JOIN alert_events e ON e.id = d.event_id "
                        + "WHERE d.user_id = ? ORDER BY d.created_at DESC, d.id DESC LIMIT ?",
                (rs, rowNum) -> new InboxItem(rs.getLong("id"), rs.getString("kind"),
                        rs.getDate("occurred_on").toLocalDate(), rs.getString("title"), rs.getString("body"),
                        rs.getBoolean("late"), UserStore.instant(rs.getTimestamp("created_at")),
                        UserStore.instant(rs.getTimestamp("read_at"))),
                userId, limit);
    }

    public int unreadCount(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_deliveries "
                + "WHERE user_id = ? AND read_at IS NULL AND NOT late", Integer.class, userId);
        return count == null ? 0 : count;
    }

    public int markRead(long userId, List<Long> ids) {
        if (ids == null) {
            return jdbc.update("UPDATE alert_deliveries SET read_at = now() WHERE user_id = ? AND read_at IS NULL", userId);
        }
        int changed = 0;
        for (Long id : ids) {
            changed += jdbc.update("UPDATE alert_deliveries SET read_at = now() "
                    + "WHERE id = ? AND user_id = ? AND read_at IS NULL", id, userId);
        }
        return changed;
    }

    /** 알림함 항목은 90일 보관합니다. 사건은 지우지 않습니다(중복 판정 키이자 기록). */
    public int purgeDeliveries(int days) {
        return jdbc.update("DELETE FROM alert_deliveries WHERE created_at < now() - make_interval(days => ?)", days);
    }

    private RowMapper<Rule> ruleMapper() {
        return (rs, rowNum) -> new Rule(rs.getLong("id"), rs.getLong("user_id"), rs.getString("kind"),
                mapper.readTree(rs.getString("params")), rs.getBoolean("enabled"),
                UserStore.instant(rs.getTimestamp("created_at")));
    }
}
