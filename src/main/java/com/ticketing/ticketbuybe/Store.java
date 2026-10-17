package com.ticketing.ticketbuybe;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class Store {
    final JdbcTemplate db;
    public Store(JdbcTemplate db) { this.db=db; }
    Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw new ApiError(404,"NOT_FOUND","리소스를 찾을 수 없습니다.");
        return rows.getFirst();
    }
    long count(String sql,Object... args) { return db.queryForObject(sql,Long.class,args); }
    static long num(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    static Instant instant(Map<String,Object> row,String key) {
        Object v=row.get(key);
        if(v instanceof OffsetDateTime t) return t.toInstant();
        if(v instanceof java.sql.Timestamp t) return t.toInstant();
        return Instant.parse(v.toString());
    }
    static OffsetDateTime utc(Instant t) { return t.atOffset(ZoneOffset.UTC); }
    static String id() { return UUID.randomUUID().toString(); }
    Map<String,Object> lockPerformance(long id) { return one("select * from performance where id=? for update",id); }
}
