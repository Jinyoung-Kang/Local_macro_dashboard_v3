package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;

/**
 * 사용자별 하루 사용량({@code usage_counters}) — 유료 API 비용 상한(ADR 0010).
 */
@Repository
public class UsageStore {

    private final JdbcTemplate jdbc;

    public UsageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 상한 안이면 1 늘리고 true. 이미 상한이면 늘리지 않고 false. 동시에 불러도 상한을 넘지 않습니다(조건부 UPSERT).
     */
    public boolean tryIncrement(long userId, LocalDate day, String kind, int limit) {
        Integer count = jdbc.query("INSERT INTO usage_counters (user_id, day, kind, count) VALUES (?, ?, ?, 1) "
                        + "ON CONFLICT (user_id, day, kind) DO UPDATE SET count = usage_counters.count + 1 "
                        + "WHERE usage_counters.count < ? RETURNING count",
                (rs, rowNum) -> rs.getInt("count"), userId, Date.valueOf(day), kind, limit).stream()
                .findFirst().orElse(null);
        return count != null && count <= limit;
    }

    /** 성공하지 못한 호출의 사용량을 되돌립니다(엔진이 모두 실패했을 때 — 쓰지 못한 것까지 세지 않음). */
    public void decrement(long userId, LocalDate day, String kind) {
        jdbc.update("UPDATE usage_counters SET count = count - 1 WHERE user_id = ? AND day = ? AND kind = ? AND count > 0",
                userId, Date.valueOf(day), kind);
    }

    public int count(long userId, LocalDate day, String kind) {
        Integer count = jdbc.query("SELECT count FROM usage_counters WHERE user_id = ? AND day = ? AND kind = ?",
                (rs, rowNum) -> rs.getInt("count"), userId, Date.valueOf(day), kind).stream().findFirst().orElse(0);
        return count;
    }
}
