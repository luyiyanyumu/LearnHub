package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Optional MySQL checks against the migrated schema; JdbcTest rolls every check back. */
@JdbcTest(properties = "spring.sql.init.mode=never")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LearningActivityService.class)
@EnabledIfEnvironmentVariable(named = "LEARN_HUB_DB_TESTS", matches = "true")
class LearningActivityIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LearningActivityService activity;

    @Test
    void repeatedSavesCountOnceAndPreserveThePreviousDay() {
        LocalDate today = jdbc.queryForObject("SELECT CURRENT_DATE()", Date.class).toLocalDate();
        long sourceId = Long.MAX_VALUE;
        jdbc.update("""
                INSERT INTO learning_activity (source_type, source_id, activity_date)
                VALUES ('activity_test', ?, ?)
                """, sourceId, Date.valueOf(today.minusDays(1)));

        activity.record("activity_test", sourceId);
        activity.record("activity_test", sourceId);

        assertEquals(2L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning_activity
                WHERE source_type = 'activity_test' AND source_id = ?
                """, Long.class, sourceId));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning_activity
                WHERE source_type = 'activity_test' AND source_id = ? AND activity_date = ?
                """, Long.class, sourceId, Date.valueOf(today.minusDays(1))));
    }

    @Test
    void annualTotalsIncludeLeapDayAndExcludeAdjacentYears() {
        long baseline = ((Number) activity.activity(2024).get("total")).longValue();
        for (String date : List.of("2023-12-31", "2024-01-01", "2024-02-29", "2024-12-31", "2025-01-01")) {
            jdbc.update("""
                    INSERT INTO learning_activity (source_type, source_id, activity_date)
                    VALUES ('activity_test', ?, ?)
                    """, Long.MAX_VALUE, Date.valueOf(date));
        }
        Map<String, Object> result = activity.activity(2024);
        assertEquals(baseline + 3, ((Number) result.get("total")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) result.get("days");
        assertEquals("2024-01-01", days.getFirst().get("date"));
        assertEquals("2024-12-31", days.getLast().get("date"));
        assertEquals(1, days.stream().filter(day -> "2024-02-29".equals(day.get("date"))).count());
    }
}
