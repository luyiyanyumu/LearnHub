package org.dyh.learnhub.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real mapper SQL against an isolated migrated database; never reads the application's database URL. */
@EnabledIfEnvironmentVariable(named = "LEARNHUB_MIGRATION_TESTS", matches = "true")
class KnowledgeMaintenanceSourceMapperTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
            System.getenv().getOrDefault("LEARNHUB_MIGRATION_TEST_IMAGE", "mysql:8.4"))
            .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("knowledge_source_mapper_test")
            .withUsername("source_mapper_test")
            .withPassword("synthetic-source-mapper-password");
    private static final long CATEGORY = 91001L;
    private static final long CHILD_CATEGORY = 91002L;
    private static final long TAG = 92001L;
    private static final long NOTE = 93001L;
    private static final long REF = 94001L;
    private static final long FILE = 95001L;
    private static JdbcTemplate db;
    private static KnowledgeMaintenanceSourceMapper mapper;

    @BeforeAll static void migrateAndBindActualMapper() throws Exception {
        MYSQL.start();
        DriverManagerDataSource source = new DriverManagerDataSource(MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .baselineVersion("0").validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        db = new JdbcTemplate(source);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(KnowledgeMaintenanceSourceMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(KnowledgeMaintenanceSourceMapper.class);
    }

    @AfterAll static void removeOnlyOwnedDisposableContainer() { MYSQL.stop(); }

    @Test void rawSourceMetadataTracksFullTextTailStatusDeletionAndTaxonomyThroughRealMyBatis() throws Exception {
        String noteBody = "合成正文😀\n".repeat(16000) + "尾甲";
        String fileBody = "仅供自动维护测试的合成资料。\n".repeat(9000) + "尾甲";
        String referenceBody = "合成速查正文，关系与公式仅用于测试。";
        String summary = "合成资料说明";
        assertThat(noteBody.length()).isGreaterThan(100000);
        assertThat(fileBody.length()).isGreaterThan(100000);
        db.update("INSERT INTO category(id,name,parent_id) VALUES(?,?,0)", CATEGORY, "合成根分类");
        db.update("INSERT INTO category(id,name,parent_id) VALUES(?,?,?)", CHILD_CATEGORY, "合成子分类", CATEGORY);
        db.update("INSERT INTO tag(id,name) VALUES(?,?)", TAG, "合成标签");
        db.update("INSERT INTO note(id,title,content,category_id) VALUES(?,?,?,?)", NOTE, "合成长笔记", noteBody, CHILD_CATEGORY);
        db.update("INSERT INTO quick_ref(id,title,content,category_id) VALUES(?,?,?,?)", REF, "合成速查卡", referenceBody, CATEGORY);
        db.update("INSERT INTO file_info(id,origin_name,store_name,size,category_id,summary,text_content,text_status) "
                + "VALUES(?,?,?,?,?,?,?,'ok')", FILE, "合成资料.txt", "synthetic-source-mapper.txt", 1024L, CHILD_CATEGORY, summary, fileBody);
        db.update("INSERT INTO note_tag(note_id,tag_id) VALUES(?,?)", NOTE, TAG);

        List<Map<String, Object>> initial = mapper.sources();
        Map<String, Object> note = find(initial, "note", NOTE);
        Map<String, Object> ref = find(initial, "quick_ref", REF);
        Map<String, Object> file = find(initial, "file", FILE);
        for (Map<String, Object> row : List.of(note, ref, file)) {
            assertThat(row).containsOnlyKeys("sourceType", "id", "title", "categoryId", "contentHash", "contentChars", "textStatus");
            assertThat(row.get("contentHash")).asString().matches("[0-9a-f]{64}");
        }
        assertThat(note).containsEntry("title", "合成长笔记").containsEntry("textStatus", "");
        assertThat(number(note, "categoryId")).isEqualTo(CHILD_CATEGORY);
        assertThat(number(note, "contentChars")).isEqualTo(noteBody.codePointCount(0, noteBody.length()));
        assertThat(note.get("contentHash")).isEqualTo(sha256(noteBody));
        assertThat(ref).containsEntry("title", "合成速查卡").containsEntry("contentHash", sha256(referenceBody));
        assertThat(file).containsEntry("title", "合成资料.txt").containsEntry("textStatus", "ok")
                .containsEntry("contentHash", sha256(summary + "\n" + fileBody));
        assertThat(number(file, "contentChars")).isEqualTo((summary + fileBody).codePointCount(0, (summary + fileBody).length()));
        assertThat(initial).extracting(row -> row.get("sourceType") + ":" + String.format("%020d", number(row, "id")))
                .isSorted();

        Map<String, Object> child = mapper.categories().stream().filter(row -> number(row, "id") == CHILD_CATEGORY).findFirst().orElseThrow();
        assertThat(child).containsOnlyKeys("id", "parentId", "name").containsEntry("name", "合成子分类");
        assertThat(number(child, "parentId")).isEqualTo(CATEGORY);
        assertThat(mapper.tags().stream().filter(row -> number(row, "id") == TAG).findFirst().orElseThrow())
                .containsOnlyKeys("id", "name").containsEntry("name", "合成标签");
        Map<String, Object> relation = mapper.noteTags().stream().filter(row -> number(row, "noteId") == NOTE).findFirst().orElseThrow();
        assertThat(relation).containsOnlyKeys("noteId", "tagId");
        assertThat(number(relation, "tagId")).isEqualTo(TAG);

        // Same length and identical first 100k characters: a prefix hash or size/timestamp heuristic cannot pass.
        String noteTailChanged = noteBody.substring(0, noteBody.length() - 1) + "乙";
        db.update("UPDATE note SET content=? WHERE id=?", noteTailChanged, NOTE);
        Map<String, Object> updatedNote = find(mapper.sources(), "note", NOTE);
        assertThat(updatedNote.get("contentHash")).isNotEqualTo(note.get("contentHash")).isEqualTo(sha256(noteTailChanged));
        assertThat(updatedNote.get("contentChars")).isEqualTo(note.get("contentChars"));
        String fileTailChanged = fileBody.substring(0, fileBody.length() - 1) + "乙";
        db.update("UPDATE file_info SET text_content=? WHERE id=?", fileTailChanged, FILE);
        Map<String, Object> updatedFile = find(mapper.sources(), "file", FILE);
        assertThat(updatedFile.get("contentHash")).isNotEqualTo(file.get("contentHash")).isEqualTo(sha256(summary + "\n" + fileTailChanged));
        assertThat(updatedFile.get("contentChars")).isEqualTo(file.get("contentChars"));

        db.update("UPDATE file_info SET text_status='failed' WHERE id=?", FILE);
        Map<String, Object> failedFile = find(mapper.sources(), "file", FILE);
        assertThat(failedFile).containsEntry("textStatus", "failed").containsEntry("contentHash", updatedFile.get("contentHash"));
        db.update("UPDATE file_info SET summary=?,text_content=NULL,text_status='empty' WHERE id=?", "合成说明已变更", FILE);
        Map<String, Object> emptyFile = find(mapper.sources(), "file", FILE);
        assertThat(emptyFile).containsEntry("textStatus", "empty").containsEntry("contentHash", sha256("合成说明已变更\n"));
        assertThat(number(emptyFile, "contentChars")).isEqualTo("合成说明已变更".length());
        db.update("UPDATE quick_ref SET content=NULL WHERE id=?", REF);
        assertThat(find(mapper.sources(), "quick_ref", REF)).containsEntry("contentHash", sha256(""));
        assertThat(number(find(mapper.sources(), "quick_ref", REF), "contentChars")).isZero();

        db.update("UPDATE category SET parent_id=0,name=? WHERE id=?", "合成分类已更名", CHILD_CATEGORY);
        Map<String, Object> movedCategory = mapper.categories().stream().filter(row -> number(row, "id") == CHILD_CATEGORY).findFirst().orElseThrow();
        assertThat(movedCategory).containsEntry("name", "合成分类已更名");
        assertThat(number(movedCategory, "parentId")).isZero();
        db.update("UPDATE tag SET name=? WHERE id=?", "合成标签已更名", TAG);
        assertThat(mapper.tags().stream().filter(row -> number(row, "id") == TAG).findFirst().orElseThrow())
                .containsEntry("name", "合成标签已更名");
        db.update("DELETE FROM note_tag WHERE note_id=? AND tag_id=?", NOTE, TAG);
        assertThat(mapper.noteTags()).noneMatch(row -> number(row, "noteId") == NOTE);
        db.update("DELETE FROM note WHERE id=?", NOTE);
        db.update("DELETE FROM quick_ref WHERE id=?", REF);
        db.update("DELETE FROM file_info WHERE id=?", FILE);
        assertThat(mapper.sources()).noneMatch(row -> List.of(NOTE, REF, FILE).contains(number(row, "id")));
        db.update("DELETE FROM category WHERE id=?", CHILD_CATEGORY);
        db.update("DELETE FROM tag WHERE id=?", TAG);
        assertThat(mapper.categories()).noneMatch(row -> number(row, "id") == CHILD_CATEGORY);
        assertThat(mapper.tags()).noneMatch(row -> number(row, "id") == TAG);
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, String type, long id) {
        return rows.stream().filter(row -> type.equals(row.get("sourceType")) && number(row, "id") == id).findFirst().orElseThrow();
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
