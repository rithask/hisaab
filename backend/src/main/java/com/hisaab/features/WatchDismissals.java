package com.hisaab.features;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/** Alerts I've reviewed and marked "looks fine", shared by subscription watch and unusual activity. */
@Service
public class WatchDismissals {
    private final NamedParameterJdbcTemplate jdbc;

    public WatchDismissals(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> all() {
        return new HashSet<>(jdbc.queryForList("select key from watch_dismiss", new MapSqlParameterSource(), String.class));
    }

    public void dismiss(String key) {
        if (key == null || key.isBlank() || key.length() > 300) throw new IllegalArgumentException("Unknown alert");
        jdbc.update("insert into watch_dismiss (key) values (:k) on conflict do nothing", new MapSqlParameterSource("k", key));
    }

    public void restore(String key) {
        jdbc.update("delete from watch_dismiss where key = :k", new MapSqlParameterSource("k", key));
    }
}
