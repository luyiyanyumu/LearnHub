package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表脚本与实体是否一致 —— 防的是这一类事故：
 *
 * <p>{@code spring.sql.init.mode=always} 每次启动都会执行 {@code schema.sql}，但里面用的是
 * {@code CREATE TABLE IF NOT EXISTS}：**表一旦建过，加列再也不会生效**。于是"实体加了字段、
 * 建表脚本也加了列"，只有**已经存在的老库**拿不到这一列，运行时直接
 * {@code Unknown column 'xxx' in 'field list'}。
 *
 * <p>实测踩过一次：新部署的机器上语义索引重建整体失败，job 报
 * {@code Unknown column 'heading' in 'field list'} —— {@code kb_chunk} 少了 {@code heading} 列，
 * 而**开发机上一直没暴露**，因为那台机器的库是这列还在的早期版本建的。
 * 也就是说：**跑现有测试永远抓不到它**，只有对着"按当前 schema 新建的库"才看得见。
 *
 * <p>所以这个用例不连数据库：直接从 {@code schema.sql} 解析出"建表时会创建哪些列"
 * （包括末尾那些自愈式 {@code ALTER TABLE ... ADD COLUMN}），再与实体的持久化字段对账。
 * 纯文本解析，任何时候都能跑。
 */
class SchemaEntityAlignmentTest {

    /** MyBatis-Plus 的字段名 → 列名：驼峰转下划线（与全局配置一致） */
    private static String toSnake(String camel) {
        return camel.replaceAll("(?<=[a-z0-9])([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }

    /** 从 schema.sql 里解析：表名 → 该表最终会有的列集合 */
    private static Map<String, Set<String>> schemaColumns(String sql) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        // ① CREATE TABLE [IF NOT EXISTS] t (...)
        Matcher create = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\((.*?)\\n\\)\\s*ENGINE",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        while (create.find()) {
            String table = create.group(1).toLowerCase(Locale.ROOT);
            Set<String> cols = out.computeIfAbsent(table, k -> new LinkedHashSet<>());
            for (String line : create.group(2).split("\n")) {
                Matcher col = Pattern.compile("^\\s*`?(\\w+)`?\\s+[A-Za-z]").matcher(line);
                if (!col.find()) {
                    continue;
                }
                String name = col.group(1).toLowerCase(Locale.ROOT);
                // 跳过约束行（PRIMARY KEY / UNIQUE KEY / KEY / INDEX / CONSTRAINT）
                if (Set.of("primary", "unique", "key", "index", "constraint", "foreign").contains(name)) {
                    continue;
                }
                cols.add(name);
            }
        }
        // ② 末尾的自愈式补列：ALTER TABLE t ADD COLUMN c ...（它同样会写进建表结果）
        Matcher alter = Pattern.compile(
                "ALTER\\s+TABLE\\s+`?(\\w+)`?\\s+ADD\\s+COLUMN\\s+`?(\\w+)`?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        while (alter.find()) {
            out.computeIfAbsent(alter.group(1).toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                    .add(alter.group(2).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 从一个实体源码里取：表名 + 持久化字段（跳过 @TableField(exist = false) 的非表字段） */
    private record Entity(String file, String table, List<String> columns) {
    }

    private static Entity parseEntity(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String table = null;
        List<String> cols = new ArrayList<>();
        // 逐行扫描：记住"当前字段头上有没有 @TableField(exist = false)"，遇到字段声明就结算。
        // 不用一次性大正则：注解与字段的配对很容易被非贪婪量词吃掉（踩过：把已声明 exist=false
        // 的 children 又当成了表字段，于是用例误报）。
        boolean transientField = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (table == null) {
                Matcher tn = Pattern.compile("@TableName\\(\\s*\"([^\"]+)\"").matcher(line);
                if (tn.find()) {
                    table = tn.group(1);
                }
            }
            if (line.startsWith("@TableField")) {
                transientField = line.replace(" ", "").contains("exist=false");
                continue;
            }
            Matcher decl = Pattern.compile("^(?:private|protected)\\s+[\\w<>\\[\\], .?]+\\s+(\\w+)\\s*;").matcher(line);
            if (decl.find()) {
                String name = decl.group(1);
                if (!transientField && !name.equals("serialVersionUID")) {
                    cols.add(toSnake(name));
                }
                transientField = false;
            }
        }
        if (table == null) {
            table = toSnake(file.getFileName().toString().replace(".java", ""));
        }
        return new Entity(file.getFileName().toString(), table, cols);
    }

    @Test
    @DisplayName("表结构漂移：schema.sql 建不出的列，实体里不能有（只在全新库上会炸）")
    void entityFieldsExistInSchema() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path schema = root.resolve("src/main/resources/schema.sql");
        Path entities = root.resolve("src/main/java/org/dyh/learnhub/entity");
        assertTrue(Files.exists(schema), "找不到 schema.sql: " + schema);
        assertTrue(Files.isDirectory(entities), "找不到 entity 目录: " + entities);

        Map<String, Set<String>> schemaCols = schemaColumns(Files.readString(schema, StandardCharsets.UTF_8));
        List<String> problems = new ArrayList<>();
        List<Entity> checked = new ArrayList<>();
        try (Stream<Path> s = Files.list(entities)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                Entity e = parseEntity(f);
                Set<String> cols = schemaCols.get(e.table().toLowerCase(Locale.ROOT));
                if (cols == null) {
                    problems.add(String.format("%s：表 %s 在 schema.sql 里没有建表语句", e.file(), e.table()));
                    continue;
                }
                checked.add(e);
                for (String c : e.columns()) {
                    if (!cols.contains(c)) {
                        problems.add(String.format(
                                "%s：表 %s 缺列 `%s`（实体字段写它，但 schema.sql 建不出来 → "
                                        + "全新部署的库会在运行时报 Unknown column）",
                                e.file(), e.table(), c));
                    }
                }
            }
        }
        assertTrue(checked.size() >= 15, "只解析到 " + checked.size() + " 个实体，解析逻辑可能失效了");
        assertTrue(problems.isEmpty(), "实体与建表脚本漂移：\n  - " + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("自愈式补列：schema.sql 必须能在已存在的库上补齐它自己建不出的老列")
    void knownDriftIsPatchedForExistingDatabases() throws Exception {
        Path schema = Path.of(System.getProperty("user.dir"), "src/main/resources/schema.sql");
        String sql = Files.readString(schema, StandardCharsets.UTF_8);
        // kb_chunk.heading 是实测踩过的那一处：老库没有它，必须靠幂等 ALTER 补上
        assertTrue(sql.matches("(?s).*ALTER\\s+TABLE\\s+kb_chunk\\s+ADD\\s+COLUMN\\s+heading.*"),
                "kb_chunk.heading 缺少自愈式补列语句 —— 已存在的库不会拿到这一列");
        // 每条自愈补列都要先查 information_schema 再决定执不执行（幂等，反复启动无副作用）
        assertTrue(sql.contains("information_schema.COLUMNS") && sql.contains("PREPARE stmt FROM @ddl"),
                "自愈补列必须走 information_schema + PREPARE 的幂等写法（与 rag_eval.note 那段一致）");
    }
}
