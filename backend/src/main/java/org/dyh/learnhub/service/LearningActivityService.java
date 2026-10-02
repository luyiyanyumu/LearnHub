package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Persistent daily contributions; later edits or deletions do not move past activity. */
@Service
@RequiredArgsConstructor
public class LearningActivityService {

    private final JdbcTemplate jdbc;

    /** One contribution per source and day. A user message has its own event id. */
    public void record(String sourceType, Long sourceId) {
        jdbc.update("""
                INSERT IGNORE INTO learning_activity (source_type, source_id, activity_date)
                VALUES (?, ?, CURRENT_DATE())
                """, sourceType, sourceId);
    }

    public Map<String, Object> activity(Integer requestedYear) {
        int currentYear = LocalDate.now().getYear();
        int year = requestedYear == null ? currentYear : requestedYear;
        if (year < 2000 || year > currentYear + 1) year = currentYear;
        LocalDate start = LocalDate.of(year, 1, 1);
        List<Map<String, Object>> days = jdbc.query("""
                SELECT activity_date, COUNT(*) AS activity_count
                FROM learning_activity
                WHERE activity_date >= ? AND activity_date < ?
                GROUP BY activity_date
                ORDER BY activity_date
                """, (rs, row) -> Map.<String, Object>of(
                "date", rs.getDate("activity_date").toLocalDate().toString(),
                "count", rs.getLong("activity_count")),
                Date.valueOf(start), Date.valueOf(start.plusYears(1)));
        long total = days.stream().mapToLong(day -> ((Number) day.get("count")).longValue()).sum();
        long max = days.stream().mapToLong(day -> ((Number) day.get("count")).longValue()).max().orElse(0);
        return Map.of("year", year, "total", total, "activeDays", days.size(), "max", max, "days", days);
    }
}
