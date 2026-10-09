package org.dyh.learnhub.config;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real MySQL migration tests. Only disposable databases in a dedicated container
 * are accessed; no application.yml database URL or existing LearnHub data is used.
 */
@EnabledIfEnvironmentVariable(named = "LEARNHUB_MIGRATION_TESTS", matches = "true")
class DatabaseMigrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault("LEARNHUB_MIGRATION_TEST_IMAGE", "mysql:8.4"))
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("migration_test")
            .withUsername("migration_test")
            .withPassword("migration-test-password");
    private static final List<String> CREATED = new ArrayList<>();

    @BeforeAll
    static void startDisposableMySql() {
        MYSQL.start();
    }

    @AfterAll
    static void removeOnlyDisposableDatabases() {
        try {
            JdbcTemplate admin = jdbc(MYSQL.getJdbcUrl());
            for (String database : CREATED) {
                admin.execute("DROP DATABASE `" + database + "`");
            }
        } finally {
            MYSQL.stop();
        }
    }

    private static String emptyDatabase() {
        String database = "learnhub_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc(MYSQL.getJdbcUrl()).execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        CREATED.add(database);
        return MYSQL.getJdbcUrl().replace("/migration_test", "/" + database);
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private static ApplicationContextRunner application(String url, String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, SqlInitializationAutoConfiguration.class))
                .withUserConfiguration(DatabaseMigrationConfiguration.class)
                .withInitializer(context -> {
                    try {
                        for (var source : new YamlPropertySourceLoader().load("application",
                                new ClassPathResource("application.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                        "spring.datasource.password=" + MYSQL.getPassword())
                .withPropertyValues(properties);
    }

    private static void migrate(String url) {
        application(url).run(context -> assertThat(context).hasNotFailed().hasSingleBean(Flyway.class));
    }

    private static List<Map<String, Object>> structure(String url) {
        return jdbc(url).queryForList("""
                SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME != 'flyway_schema_history'
                ORDER BY TABLE_NAME, COLUMN_NAME
                """);
    }

    private static List<Map<String, Object>> indexes(String url) {
        return jdbc(url).queryForList("""
                SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME != 'flyway_schema_history'
                ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
                """);
    }

    private static void assertEveryPersistentEntityColumnExists(String url) throws Exception {
        JdbcTemplate db = jdbc(url);
        try (var sources = Files.list(Path.of("src/main/java/org/dyh/learnhub/entity"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Class<?> entity = Class.forName("org.dyh.learnhub.entity."
                        + source.getFileName().toString().replace(".java", ""));
                TableName annotation = entity.getAnnotation(TableName.class);
                String table = annotation == null ? snake(entity.getSimpleName()) : annotation.value();
                List<String> actual = db.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, table);
                assertThat(actual).as("table for %s", entity.getSimpleName()).isNotEmpty();
                for (var field : entity.getDeclaredFields()) {
                    TableField mapped = field.getAnnotation(TableField.class);
                    if (Modifier.isStatic(field.getModifiers()) || (mapped != null && !mapped.exist())) {
                        continue;
                    }
                    TableId id = field.getAnnotation(TableId.class);
                    String column = mapped != null && !mapped.value().isEmpty() ? mapped.value()
                            : id != null && !id.value().isEmpty() ? id.value() : snake(field.getName());
                    assertThat(actual).as("%s.%s", table, column).contains(column);
                }
            }
        }
    }

    private static String snake(String camel) {
        return camel.replaceAll("(?<=[a-z0-9])([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }

    @Test
    void freshDatabaseHasAllTablesIndexesAndSeedsExactlyOnce() throws Exception {
        String url = emptyDatabase();
        migrate(url);
        JdbcTemplate db = jdbc(url);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                Integer.class)).isEqualTo(29); // 28 application tables and Flyway history
        assertThat(db.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isEqualTo(2);
        assertEveryPersistentEntityColumnExists(url);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_wiki_dependency_page'",
                Integer.class)).isEqualTo(1);

        db.update("DELETE FROM note_tag");
        db.update("DELETE FROM note");
        migrate(url);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(3);
    }

    @Test
    void legacyDatabaseIsBaselinedAtZeroAndUpgradesToTheSameStructureWithoutLosingContent() throws Exception {
        String freshUrl = emptyDatabase();
        migrate(freshUrl);
        String url = emptyDatabase();
        try (Connection connection = jdbc(url).getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/legacy/learnhub_before_flyway.sql"));
        }
        migrate(url);
        JdbcTemplate db = jdbc(url);
        assertThat(db.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("0", "1", "2", "3");
        assertThat(structure(url)).containsExactlyElementsOf(structure(freshUrl));
        assertThat(indexes(url)).containsExactlyElementsOf(indexes(freshUrl));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT content FROM note WHERE id = 9001", String.class)).isEqualTo("# 用户自己的正文");
        assertThat(db.queryForObject("SELECT api_key FROM model_profile WHERE id = 'keep-profile'", String.class))
                .isEqualTo("synthetic-test-key");
        assertThat(db.queryForObject("SELECT setting_value FROM app_setting WHERE setting_key = 'custom.setting'", String.class))
                .isEqualTo("keep-me");
        assertThat(db.queryForObject("SELECT content_md FROM wiki_page WHERE id = 9001", String.class)).isEqualTo("用户 Wiki 正文");
        assertThat(db.queryForObject("SELECT text_status FROM file_info WHERE id = 9001", String.class)).isEqualTo("pending");
        assertThat(db.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rag_eval' AND COLUMN_NAME = 'note'", Integer.class))
                .isEqualTo(1000);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_activity WHERE source_type = 'note' AND source_id = 9001",
                Integer.class)).isEqualTo(2);
        migrate(url);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(4);
    }

    @Test
    void versionTwoUpgradePreservesProfilesContentAndVectorsUntilExplicitRebuild() {
        String url = emptyDatabase();
        application(url, "spring.flyway.target=2")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(Flyway.class));
        JdbcTemplate db = jdbc(url);
        db.update("INSERT INTO model_profile (id, name, provider, base_url, api_key, model) "
                + "VALUES ('old-chat', '原有档案', 'custom', 'http://127.0.0.1:9/v1', 'synthetic-key', 'chat-model')");
        db.update("INSERT INTO note (id, title, content) VALUES (92001, '保留笔记', '用户原有正文')");
        byte[] oldVector = new byte[] {0, 0, (byte) 128, 63, 0, 0, 0, 64};
        db.update("INSERT INTO kb_chunk (source_type, source_id, seq, chunk_text, vec, dim, model) "
                + "VALUES ('note', 92001, 0, '原有分块', ?, 2, 'old-embed')", oldVector);
        db.update("INSERT INTO kb_index_state (id, source_type, source_id, content_hash, chunks) "
                + "VALUES ('note:92001', 'note', 92001, 'original-content-hash', 1)");
        db.update("INSERT INTO kg_node (id, name, norm, embedding) "
                + "VALUES ('e-old', '原有实体', '原有实体', ?)", oldVector);

        migrate(url);
        assertThat(db.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3");
        assertThat(db.queryForObject("SELECT purpose FROM model_profile WHERE id = 'old-chat'", String.class))
                .isEqualTo("chat");
        assertThat(db.queryForObject("SELECT api_key FROM model_profile WHERE id = 'old-chat'", String.class))
                .isEqualTo("synthetic-key");
        assertThat(db.queryForObject("SELECT content FROM note WHERE id = 92001", String.class))
                .isEqualTo("用户原有正文");
        assertThat(db.queryForObject("SELECT vec FROM kb_chunk WHERE source_id = 92001", byte[].class))
                .containsExactly(oldVector);
        assertThat(db.queryForObject("SELECT embedding FROM kg_node WHERE id = 'e-old'", byte[].class))
                .containsExactly(oldVector);
        assertThat(db.queryForObject("SELECT embedding_space FROM kb_chunk WHERE source_id = 92001", String.class))
                .isNull();
        assertThat(db.queryForObject("SELECT embedding_space FROM kb_index_state WHERE id = 'note:92001'", String.class))
                .isNull();
        assertThat(db.queryForObject("SELECT embedding_space FROM kg_node WHERE id = 'e-old'", String.class))
                .isNull();
        assertThat(db.queryForObject("SELECT content_hash FROM kb_index_state WHERE id = 'note:92001'", String.class))
                .isEqualTo("original-content-hash");
        migrate(url);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(3);
    }

    @Test
    void unrelatedNonEmptyDatabaseFailsBeforeAnySchemaChanges() {
        String url = emptyDatabase();
        JdbcTemplate db = jdbc(url);
        db.execute("CREATE TABLE unrelated (id INT PRIMARY KEY, content TEXT)");
        db.update("INSERT INTO unrelated VALUES (1, 'keep')");
        application(url).run(context -> assertThat(context).hasFailed());
        assertThat(db.queryForList("SHOW TABLES", String.class)).containsExactly("unrelated");
        assertThat(db.queryForObject("SELECT content FROM unrelated WHERE id = 1", String.class)).isEqualTo("keep");
    }

    @Test
    void baselineOneCannotSkipLegacyCompatibilityUpgrade() throws Exception {
        String url = emptyDatabase();
        JdbcTemplate db = jdbc(url);
        try (Connection connection = db.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/legacy/learnhub_before_flyway.sql"));
        }
        application(url, "spring.flyway.baseline-version=1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Legacy LearnHub databases must be baselined at version 0 so V1 compatibility upgrades are not skipped.");
        });
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'flyway_schema_history'", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isEqualTo(1);
    }

    @Test
    void checksumsAreValidatedAndChangedHistoryBlocksStartup() {
        String url = emptyDatabase();
        migrate(url);
        jdbc(url).update("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
        application(url).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void newerDatabaseBlocksOlderApplicationStartup() {
        String url = emptyDatabase();
        migrate(url);
        jdbc(url).update("""
                INSERT INTO flyway_schema_history
                (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES (4, '999', 'future release', 'SQL', 'V999__future.sql', 1, 'root', 0, true)
                """);
        application(url).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void migrationSqlErrorsBlockStartupAndRemainRecordedAsFailures() {
        String url = emptyDatabase();
        application(url, "spring.flyway.locations=classpath:db/migration,classpath:db/intentional-failure")
                .run(context -> assertThat(context).hasFailed());
        assertThat(jdbc(url).queryForObject("SELECT success FROM flyway_schema_history WHERE version = '4'", Boolean.class))
                .isFalse();
    }
}
