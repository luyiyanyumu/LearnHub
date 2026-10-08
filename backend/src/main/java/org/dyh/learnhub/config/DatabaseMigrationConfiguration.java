package org.dyh.learnhub.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Adopt only recognizable pre-Flyway LearnHub databases; migration errors fail startup. */
@Configuration(proxyBeanMethods = false)
public class DatabaseMigrationConfiguration {

    private static final Map<String, Set<String>> LEGACY_SIGNATURE = Map.of(
            "category", Set.of("id", "name", "parent_id"),
            "tag", Set.of("id", "name"),
            "note", Set.of("id", "title", "content", "category_id"),
            "note_tag", Set.of("note_id", "tag_id"),
            "quick_ref", Set.of("id", "title", "content", "category_id"),
            "file_info", Set.of("id", "origin_name", "store_name", "size"),
            "app_setting", Set.of("setting_key", "setting_value"));

    @Bean
    FlywayConfigurationCustomizer strictMigrationHistory() {
        // Flyway normally ignores migrations newer than this binary. An image downgrade
        // must fail until its database backup is restored, rather than silently serving
        // an incompatible schema. Explicit empty varargs removes that default pattern.
        return configuration -> configuration.ignoreMigrationPatterns(new String[0]);
    }

    @Bean
    FlywayMigrationStrategy learnHubMigrationStrategy() {
        return flyway -> {
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
                String schema = connection.getCatalog();
                Set<String> tables = tables(connection, schema);
                if (!tables.isEmpty() && !tables.contains(flyway.getConfiguration().getTable())) {
                    for (var signature : LEGACY_SIGNATURE.entrySet()) {
                        if (!tables.contains(signature.getKey())
                                || !columns(connection, schema, signature.getKey()).containsAll(signature.getValue())) {
                            throw new IllegalStateException("Refusing to baseline non-empty database '" + schema
                                    + "': it does not match a legacy LearnHub database. Use an empty dedicated database "
                                    + "or restore a LearnHub backup; no schema changes have been applied.");
                        }
                    }
                    if (!"0".equals(flyway.getConfiguration().getBaselineVersion().getVersion())) {
                        throw new IllegalStateException("Legacy LearnHub databases must be baselined at version 0 "
                                + "so V1 compatibility upgrades are not skipped.");
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot inspect database before migration", e);
            }
            flyway.migrate();
        };
    }

    private static Set<String> tables(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?")) {
            statement.setString(1, schema);
            return names(statement);
        }
    }

    private static Set<String> columns(Connection connection, String schema, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            return names(statement);
        }
    }

    private static Set<String> names(PreparedStatement statement) throws SQLException {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }
}
