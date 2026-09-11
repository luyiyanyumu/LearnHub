-- ============================================================================
-- 示例数据（种子）
--
-- 为什么要写成这种「啰嗦」的形式？
--   Spring Boot 配置了 spring.sql.init.mode=always，也就是**每次启动**都会执行本文件。
--   原来的写法是纯 INSERT IGNORE：它只能避免「重复插入」，但分不清
--   「示例数据没插过」和「用户把示例数据删掉了」—— 于是用户每次删完示例笔记，
--   一重启就被原样塞回来，像是删不掉。
--
--   现在的做法：在 app_setting 里记一个一次性标记 demo.seeded。
--   所有种子语句都加上 WHERE NOT EXISTS(标记) ——
--     · 全新数据库：标记不存在 → 正常导入示例，最后写入标记；
--     · 之后每次启动：标记已存在 → 全部语句都是空操作。
--   这样「示例数据只在第一次启动时出现一次」，用户删掉就不会再回来。
-- ============================================================================

-- 分类
INSERT IGNORE INTO category (id, name, parent_id, sort_order)
SELECT * FROM (
    SELECT 1 AS id, 'Java' AS name, 0 AS parent_id, 1 AS sort_order
    UNION ALL SELECT 2, 'Spring Boot', 0, 2
    UNION ALL SELECT 3, '前端', 0, 3
    UNION ALL SELECT 4, 'Linux·Docker', 0, 4
    UNION ALL SELECT 5, '面试', 0, 5
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'demo.seeded');

-- 标签
INSERT IGNORE INTO tag (id, name)
SELECT * FROM (
    SELECT 1 AS id, 'Java基础' AS name
    UNION ALL SELECT 2, '构建工具'
    UNION ALL SELECT 3, '面试高频'
    UNION ALL SELECT 4, '算法'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'demo.seeded');

-- 笔记 1：Maven 坐标
INSERT IGNORE INTO note (id, title, content, category_id)
SELECT * FROM (
    SELECT 1 AS id, 'Maven 坐标三要素（材料清单怎么填）' AS title,
'# Maven 坐标三要素

> 造价类比：坐标 = 材料的唯一编码，缺一个都领不到料。

## 三要素

| 元素 | 含义 | 类比 |
|------|------|------|
| groupId | 组织/公司标识 | 供应商名称 |
| artifactId | 项目/模块名 | 材料名称 |
| version | 版本号 | 材料规格等级 |

## 示例

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.17</version>
</dependency>
```

## 记忆点
- 坐标唯一 = (groupId, artifactId, version) 三元组唯一
- 打包后按 坐标 分层存放于本地仓库 ~/.m2' AS content,
    1 AS category_id
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'demo.seeded');

-- 笔记 2：Spring Boot 启动流程
INSERT IGNORE INTO note (id, title, content, category_id)
SELECT * FROM (
    SELECT 2 AS id, 'Spring Boot 启动流程（高频面试题）' AS title,
'# Spring Boot 启动流程

## 一句话版
`main` 方法启动 → 创建 SpringApplication → 推断应用类型 → 加载启动类上的注解（@SpringBootApplication）→ 自动装配 → 内嵌 Tomcat 启动 → 应用就绪。

## 分步拆解

1. `SpringApplication.run()` 创建并初始化应用上下文
2. `@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`
3. **自动装配**：`spring.factories` / `AutoConfiguration.imports` 里按条件（@Conditional）装配 Bean
4. 内嵌 Web 容器（Tomcat）随应用一起启动，无需外置部署

## 常被追问
- 自动装配原理？→ @EnableAutoConfiguration → AutoConfigurationImportSelector 读配置
- 条件注解有哪些？→ @ConditionalOnClass / @ConditionalOnMissingBean / @ConditionalOnProperty' AS content,
    2 AS category_id
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'demo.seeded');

-- 笔记-标签关联
INSERT IGNORE INTO note_tag (note_id, tag_id)
SELECT * FROM (
    SELECT 1 AS note_id, 1 AS tag_id
    UNION ALL SELECT 1, 2
    UNION ALL SELECT 2, 3
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'demo.seeded');

-- 速查卡 1
INSERT IGNORE INTO quick_ref (id, title, content, category_id)
SELECT * FROM (
    SELECT 1 AS id, 'String / StringBuilder / StringBuffer 区别' AS title,
'| 类型 | 可变 | 线程安全 | 性能 |
|------|------|---------|------|
| String | 不可变 | 安全(本身不可变) | 拼接慢 |
| StringBuilder | 可变 | 不安全 | 最快 |
| StringBuffer | 可变 | 安全(synchronized) | 较慢 |' AS content,
    1 AS category_id
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'demo.seeded');

-- 速查卡 2
INSERT IGNORE INTO quick_ref (id, title, content, category_id)
SELECT * FROM (
    SELECT 2 AS id, 'Docker 常用命令' AS title,
'```bash
docker ps -a              # 查看所有容器
docker exec -it 容器名 bash  # 进入容器
docker logs -f 容器名      # 看日志
docker compose up -d      # 启动编排
```' AS content,
    4 AS category_id
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'demo.seeded');

-- 速查卡 3
INSERT IGNORE INTO quick_ref (id, title, content, category_id)
SELECT * FROM (
    SELECT 3 AS id, '@GetMapping / @PostMapping 速记' AS title,
'- @GetMapping：查（SELECT）幂等
- @PostMapping：增（INSERT）非幂等
- 动词映射 REST：GET查 / POST增 / PUT改 / DELETE删' AS content,
    2 AS category_id
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM app_setting WHERE setting_key = 'demo.seeded');

-- 最后一步：写入一次性标记。放在最后，保证前面的导入真的执行过才认账。
INSERT IGNORE INTO app_setting (setting_key, setting_value) VALUES ('demo.seeded', '1');
