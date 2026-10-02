package com.macrodash.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoreRepositoryTest {

    @Test
    @DisplayName("DB 장애는 '저장본 없음'이 아니라 예외다 — 없다고 하면 수집기를 기다리게 된다")
    void databaseFailureIsNotMistakenForMissingSnapshot() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        StoreRepository repository = new StoreRepository(jdbc, new ObjectMapper());

        assertThatThrownBy(() -> repository.readSnapshot("macro.overview"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("ping은 DB에 닿으면 true, 못 닿으면 false")
    void pingReportsReachability() {
        JdbcTemplate up = mock(JdbcTemplate.class);
        when(up.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        assertThat(new StoreRepository(up, new ObjectMapper()).ping()).isTrue();

        JdbcTemplate down = mock(JdbcTemplate.class);
        when(down.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(new StoreRepository(down, new ObjectMapper()).ping()).isFalse();
    }
}
