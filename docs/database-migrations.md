# 数据库自动迁移

LearnHub 后端通过 Flyway 在启动时迁移数据库，迁移成功后才提供服务。npm 启动器更新容器后，后端会自动运行缺失的版本脚本；不再要求用户手工补字段。

## 首次安装与已有数据库

- 全新数据库执行 `V1__adopt_learnhub_schema.sql` 和 `V2__seed_new_installation.sql`：创建当前所有表、列、索引及 Wiki 来源外键，并一次性导入示例内容。
- 已有 LearnHub 数据库没有 `flyway_schema_history` 时，后端先检查分类、标签、笔记、关联、速查卡、资料、设置这 7 张核心表的签名，再以 **版本 0** 接管。随后仍执行 V1，创建缺表并补齐老表的缺列、缺索引，将 `rag_eval.note` 从 255 加宽至 1000，补历史学习日期。
- 不能使用 Flyway 默认的 baseline 版本 1，否则老库会跳过 V1 的升级。设置其他 baseline 版本时，已有数据库启动会被拒绝。
- 接管旧库时保留笔记、资料元数据、Wiki、模型档案和设置。V2 为没有旧种子标记的库补 `demo.seeded=legacy`，不重新导入用户已删除的示例内容。
- 不含这些 LearnHub 核心表的非空数据库会在写入任何迁移记录或 DDL 前被拒绝。请使用专用空库或完整的 LearnHub 备份。

默认配置为 `spring.sql.init.mode=never`；原有 `schema.sql`/`data.sql` 保留作历史参考，启动时不再执行。不要通过环境变量重新打开 SQL 初始化，或同时手工执行旧脚本。Flyway 的版本脚本才是发布后的数据库变更入口。

V1 是 2026-10-08 的不可变快照，包含该工作区已有的 Wiki 来源依赖和 GraphRAG 社区表改动。在历史幂等脚本之后，它还检查所有建表声明中的列和具名索引，补齐旧版本只修改了 `CREATE TABLE` 而未写兼容升级的字段，例如答案评测的 `wiki_inject`/`kg_inject`。存在的列值和定义不会被自动覆盖；唯一显式的类型升级是历史脚本规定的 `rag_eval.note` 加宽。

## 之后新增字段或表

1. 新增 `backend/src/main/resources/db/migration/V3__描述.sql`，之后依次使用 V4、V5；每个已发布文件的版本和内容保持不变。
2. 使用显式 `ALTER TABLE` 或 `CREATE TABLE`，将数据回填放在对应版本中。不要只修改历史 `schema.sql`，也不要修改已发布 V1/V2 的内容。
3. 如需兼容用户已手工添加的字段，可以参照 V1 的 `information_schema` 检查；不能用吞掉错误的方式掩盖迁移失败。
4. 运行下述 MySQL 新库/旧库升级测试，并随新版本更新断言和代表性旧库 fixture。

Flyway 开启脚本命名校验、checksum 校验和缺失路径校验，禁止 `clean`，禁止乱序迁移。后端同时关闭 Flyway 默认的“忽略未来版本”行为：运行过新版本迁移的数据库不能被旧版本应用直接继续使用。

## 更新失败与恢复

迁移、连接或校验失败都会阻断后端启动。日志会包含失败版本与 SQL 位置；不要修改历史脚本、直接删除历史记录或自动调用 `repair` 来绕过错误。

MySQL DDL 会隐式提交，整个迁移不是可整体回滚的事务。更新前必须保留数据库和上传文件备份；若升级已改动数据库，恢复时应恢复对应数据库备份和旧版本应用。仅换回旧镜像不保证兼容，已记录的未来迁移也会阻断旧应用启动。实际的备份/恢复入口参见 npm 启动器使用说明。

## 隔离验证

普通 `mvn verify` 默认跳过迁移测试。迁移验证显式启用 Testcontainers，Docker 需要可用；测试创建专用 MySQL 容器及随机 `learnhub_migration_<uuid>` 数据库，不使用 `application.yml` 中的本机数据库地址。测试完成后删除这些随机库并销毁容器，不访问现有 LearnHub 数据。

PowerShell：

```powershell
$env:LEARNHUB_MIGRATION_TESTS = 'true'
$env:LEARNHUB_MIGRATION_TEST_IMAGE = 'mysql:8.4'
cd backend
mvn -B -Dtest=DatabaseMigrationTest test
```

Linux/macOS/CI：

```bash
cd backend
LEARNHUB_MIGRATION_TESTS=true LEARNHUB_MIGRATION_TEST_IMAGE=mysql:8.4 \
  mvn -B -Dtest=DatabaseMigrationTest test
```

将镜像变量设为 `mysql:8.0` 可验证现有旧部署版本，默认使用 `mysql:8.4`，也可指定 MySQL 镜像源地址。测试检查新库的所有持久化实体列与外键、旧库和新库全部列定义/索引一致、旧内容/设置/模拟密钥保留、重复启动不会恢复示例、不相关库不被接管、错误 baseline 版本被拒绝、历史 checksum 变化和未来版本会阻断启动、SQL 执行失败会被记录并阻断启动。

实现遵循 [Spring Boot 3.5 数据库初始化说明](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)；baseline 只允许执行其版本以上的迁移，详见 [Flyway baseline-on-migrate](https://documentation.red-gate.com/fd/flyway-baseline-on-migrate-setting-277578974.html)。
