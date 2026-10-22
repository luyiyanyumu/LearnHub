package org.dyh.learnhub.config;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.dyh.learnhub.dto.FolderMoveRequest;
import org.dyh.learnhub.dto.FolderRequest;
import org.dyh.learnhub.mapper.ContentFolderMapper;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.NoteMapper;
import org.dyh.learnhub.service.FolderService;
import org.dyh.learnhub.service.EntityLinker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.sql.Connection;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                Integer.class)).isEqualTo(31); // 30 application tables and Flyway history
        assertThat(db.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kg_node' AND COLUMN_NAME = 'norm'", String.class))
                .isEqualTo("utf8mb4_bin");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isEqualTo(2);
        assertEveryPersistentEntityColumnExists(url);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_wiki_dependency_page'",
                Integer.class)).isEqualTo(1);

        db.update("DELETE FROM note_tag");
        db.update("DELETE FROM note");
        migrate(url);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM note", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(6);
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
                .containsExactly("0", "1", "2", "3", "4", "5", "6");
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
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(7);
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
                .containsExactly("1", "2", "3", "4", "5", "6");
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
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(6);
    }

    @Test
    void versionThreeUpgradeAddsUnfiledFoldersWithoutChangingExistingNotesFilesOrCategories() {
        String url = emptyDatabase();
        application(url, "spring.flyway.target=3").run(context -> assertThat(context).hasNotFailed());
        JdbcTemplate db = jdbc(url);
        db.update("INSERT INTO category (id, name) VALUES (93000, '原有知识分类')");
        db.update("INSERT INTO note (id, title, content, category_id) VALUES (93001, '旧笔记', '保留原文与公式', 93000)");
        db.update("INSERT INTO file_info (id, origin_name, store_name, size, category_id, summary, text_content, text_status) "
                + "VALUES (93001, '旧论文.pdf', 'original-random-name.pdf', 1024, 93000, '说明', '抽取正文', 'ok')");
        migrate(url);
        assertThat(db.queryForObject("SELECT folder_id FROM note WHERE id=93001", Long.class)).isNull();
        assertThat(db.queryForObject("SELECT folder_id FROM file_info WHERE id=93001", Long.class)).isNull();
        assertThat(db.queryForObject("SELECT content FROM note WHERE id=93001", String.class)).isEqualTo("保留原文与公式");
        assertThat(db.queryForObject("SELECT category_id FROM note WHERE id=93001", Long.class)).isEqualTo(93000);
        assertThat(db.queryForObject("SELECT store_name FROM file_info WHERE id=93001", String.class)).isEqualTo("original-random-name.pdf");
        assertThat(db.queryForObject("SELECT text_content FROM file_info WHERE id=93001", String.class)).isEqualTo("抽取正文");
        assertThat(db.queryForObject("SELECT category_id FROM file_info WHERE id=93001", Long.class)).isEqualTo(93000);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_folder", Integer.class)).isZero();
    }

    @Test
    void schemaSqlAdoptionPreservesExistingFoldersAndItemLocations() throws Exception {
        String url = emptyDatabase();
        JdbcTemplate db = jdbc(url);
        try (Connection connection = db.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        assertThat(db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kg_node' AND COLUMN_NAME = 'norm'", String.class))
                .isEqualTo("utf8mb4_bin");
        db.update("INSERT INTO content_folder (id, kind, name, parent_id) VALUES (96001, 'note', '已收纳', 0), (96002, 'file', '资料', 0)");
        db.update("INSERT INTO note (id, title, content, folder_id) VALUES (96001, '保留笔记', '正文', 96001)");
        db.update("INSERT INTO file_info (id, origin_name, store_name, folder_id) VALUES (96001, 'demo.md', 'kept.md', 96002)");
        migrate(url);
        assertThat(db.queryForObject("SELECT folder_id FROM note WHERE id=96001", Long.class)).isEqualTo(96001);
        assertThat(db.queryForObject("SELECT folder_id FROM file_info WHERE id=96001", Long.class)).isEqualTo(96002);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_folder", Integer.class)).isEqualTo(2);
        assertThat(db.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("0", "1", "2", "3", "4", "5", "6");
        migrate(url);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM content_folder", Integer.class)).isEqualTo(2);
    }

    @Test
    void versionFiveUpgradePreservesGraphAndPartialProgressWhileKeepingDistinctNormalizedNamesSeparate() throws Exception {
        String url = emptyDatabase();
        application(url, "spring.flyway.target=5").run(context -> assertThat(context).hasNotFailed());
        JdbcTemplate db = jdbc(url);
        String firstName = "SyntheticCafe";
        String secondName = "SyntheticCafé";
        String firstNorm = EntityLinker.normalize(firstName);
        String secondNorm = EntityLinker.normalize(secondName);
        String firstId = EntityLinker.id(firstName);
        String secondId = EntityLinker.id(secondName);
        String targetId = EntityLinker.id("SyntheticTarget");
        assertThat(secondNorm).isNotEqualTo(firstNorm);
        assertThat(secondId).isNotEqualTo(firstId);
        byte[] oldVector = new byte[] {0, 0, (byte) 128, 63};
        db.update("INSERT INTO kg_node (id, name, norm, aliases, brief, wiki_key, source_count, embedding, "
                        + "embedding_space, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 7, ?, "
                        + "'synthetic-space', '2025-01-01 10:00:00', '2025-01-02 10:00:00')",
                firstId, firstName, firstNorm, "SyntheticAlias", "合成节点说明", "entity:synthetic", oldVector);
        db.update("INSERT INTO kg_node (id, name, norm) VALUES (?, ?, ?)",
                targetId, "SyntheticTarget", EntityLinker.normalize("SyntheticTarget"));
        db.update("INSERT INTO kg_relation (head_id, relation, tail_id, evidence, sources, weight, origin, model) "
                        + "VALUES (?, 'part_of', ?, '合成的手工证据。', '笔记#99001', 0.75, 'manual', 'synthetic-model')",
                firstId, targetId);
        String progressKey = "internal.kg_maintenance.v1.source.资料.99001";
        String progress = "{\"version\":1,\"fingerprint\":\"synthetic-revision\",\"nextBatch\":2,"
                + "\"model\":\"synthetic-model\",\"triples\":[]}";
        db.update("INSERT INTO app_setting (setting_key, setting_value) VALUES (?, ?)", progressKey, progress);
        String nodeSnapshot = "SELECT id, name, norm, type, aliases, brief, wiki_key, source_count, "
                + "HEX(embedding) AS vector_hex, embedding_space, created_at, updated_at FROM kg_node ORDER BY id";
        var nodesBefore = db.queryForList(nodeSnapshot);
        var edgesBefore = db.queryForList("SELECT * FROM kg_relation ORDER BY id");

        // A different Java identity was rejected by the old accent-insensitive database key.
        assertThatThrownBy(() -> db.update("INSERT INTO kg_node (id, name, norm) VALUES (?, ?, ?)",
                secondId, secondName, secondNorm)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        migrate(url);
        assertThat(db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kg_node' AND COLUMN_NAME = 'norm'", String.class))
                .isEqualTo("utf8mb4_bin");
        assertThat(db.queryForList(nodeSnapshot)).containsExactlyElementsOf(nodesBefore);
        assertThat(db.queryForList("SELECT * FROM kg_relation ORDER BY id")).containsExactlyElementsOf(edgesBefore);
        assertThat(db.queryForObject("SELECT setting_value FROM app_setting WHERE setting_key = ?", String.class, progressKey))
                .isEqualTo(progress);

        db.update("INSERT INTO kg_node (id, name, norm) VALUES (?, ?, ?)", secondId, secondName, secondNorm);
        assertThat(db.queryForObject("SELECT id FROM kg_node WHERE norm = ?", String.class, firstNorm)).isEqualTo(firstId);
        assertThat(db.queryForObject("SELECT id FROM kg_node WHERE norm = ?", String.class, secondNorm)).isEqualTo(secondId);
        assertThatThrownBy(() -> db.update("INSERT INTO kg_node (id, name, norm) VALUES ('e-duplicate', ?, ?)",
                secondName, secondNorm)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        var upgradedNodes = db.queryForList(nodeSnapshot);
        // Both the migration SQL itself and subsequent application startup can be repeated safely.
        try (Connection connection = db.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V6__exact_knowledge_graph_identity.sql"));
        }
        migrate(url);
        assertThat(db.queryForList(nodeSnapshot)).containsExactlyElementsOf(upgradedNodes);
        assertThat(db.queryForList("SELECT * FROM kg_relation ORDER BY id")).containsExactlyElementsOf(edgesBefore);
        assertThat(db.queryForObject("SELECT setting_value FROM app_setting WHERE setting_key = ?", String.class, progressKey))
                .isEqualTo(progress);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='6'", Integer.class)).isEqualTo(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan("org.dyh.learnhub.mapper")
    static class FolderTestConfiguration {
        @Bean FolderService folderService(ContentFolderMapper folders, NoteMapper notes, FileInfoMapper files) {
            return new FolderService(folders, notes, files);
        }
    }

    private static ApplicationContextRunner folderApplication(String url) {
        return application(url)
                .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class))
                .withUserConfiguration(FolderTestConfiguration.class);
    }

    @Test
    void folderTransactionsValidateWholeBatchesAndDeletingLeavesPreservesContent() {
        String url = emptyDatabase();
        folderApplication(url).run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(FolderService.class);
            JdbcTemplate db = jdbc(url);
            db.update("INSERT INTO note (id, title, content) VALUES (94001, 'A', '正文 A'), (94002, 'B', '正文 B')");
            db.update("UPDATE note SET updated_at='2024-01-01 10:00:00' WHERE id IN (94001, 94002)");
            db.update("INSERT INTO file_info (id, origin_name, store_name) VALUES (94001, 'demo.pdf', 'preserve-random.pdf')");
            var parent = service.create(new FolderRequest("note", "笔记根", null));
            var leaf = service.create(new FolderRequest("note", "子目录", parent.id()));
            var fileFolder = service.create(new FolderRequest("file", "论文", null));
            assertThatThrownBy(() -> service.moveItems(new FolderMoveRequest("note", List.of(94001L, 999999L), leaf.id())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(db.queryForObject("SELECT folder_id FROM note WHERE id=94001", Long.class)).isNull();
            assertThatThrownBy(() -> service.moveItems(new FolderMoveRequest("note", List.of(94001L), fileFolder.id())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.moveItems(new FolderMoveRequest("note", List.of(94002L, 94001L), leaf.id()))).isEqualTo(2);
            assertThat(db.queryForObject("SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') FROM note WHERE id=94001", String.class))
                    .isEqualTo("2024-01-01 10:00:00");
            assertThat(service.list("note").stream().filter(folder -> folder.id().equals(leaf.id())).findFirst().orElseThrow().itemCount())
                    .isEqualTo(2);
            assertThatThrownBy(() -> service.delete(parent.id())).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.delete(leaf.id())).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM note WHERE id IN (94001, 94002) AND folder_id IS NULL", Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT content FROM note WHERE id=94001", String.class)).isEqualTo("正文 A");
            assertThat(db.queryForObject("SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') FROM note WHERE id=94001", String.class))
                    .isEqualTo("2024-01-01 10:00:00");
            service.moveItems(new FolderMoveRequest("file", List.of(94001L), fileFolder.id()));
            var file = context.getBean(FileInfoMapper.class).selectById(94001L);
            // 模拟读取旧快照后并发移动：普通元数据保存必须忽略旧目录值。
            file.setFolderId(999999L); file.setSummary("新的说明");
            context.getBean(FileInfoMapper.class).updateById(file);
            assertThat(db.queryForObject("SELECT folder_id FROM file_info WHERE id=94001", Long.class)).isEqualTo(fileFolder.id());
            assertThat(service.delete(fileFolder.id())).isEqualTo(1);
            assertThat(db.queryForObject("SELECT store_name FROM file_info WHERE id=94001", String.class)).isEqualTo("preserve-random.pdf");
        });
    }

    @Test
    void concurrentOppositeFolderMovesCannotCreateACycle() {
        String url = emptyDatabase();
        folderApplication(url).run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(FolderService.class);
            var a = service.create(new FolderRequest("note", "A", null));
            var b = service.create(new FolderRequest("note", "B", null));
            var gate = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var moveA = pool.submit(() -> attemptMove(service, a.id(), b.id(), gate));
                var moveB = pool.submit(() -> attemptMove(service, b.id(), a.id(), gate));
                gate.countDown();
                assertThat(List.of(moveA.get(15, TimeUnit.SECONDS), moveB.get(15, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            }
            var rows = service.list("note");
            assertThat(rows.stream().filter(folder -> folder.parentId() == 0).count()).isEqualTo(1);
        });
    }

    private static boolean attemptMove(FolderService service, Long self, Long parent, CountDownLatch gate) throws Exception {
        gate.await(10, TimeUnit.SECONDS);
        try {
            service.update(self, new FolderRequest(null, null, parent));
            return true;
        } catch (IllegalArgumentException rejectedCycle) {
            return false;
        }
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
                VALUES (7, '999', 'future release', 'SQL', 'V999__future.sql', 1, 'root', 0, true)
                """);
        application(url).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void migrationSqlErrorsBlockStartupAndRemainRecordedAsFailures() {
        String url = emptyDatabase();
        application(url, "spring.flyway.locations=classpath:db/migration,classpath:db/intentional-failure")
                .run(context -> assertThat(context).hasFailed());
        assertThat(jdbc(url).queryForObject("SELECT success FROM flyway_schema_history WHERE version = '7'", Boolean.class))
                .isFalse();
    }
}
