# 首页学习记录

总览页展示按自然年排列的学习记录热力图，默认今年，右侧切换最近五年。每天的方格悬浮时显示日期和记录数量，颜色深浅表示当天的活动量。

计数规则：笔记、速查卡在创建或保存时，每个条目每天计一次；每次资料上传和每条智能体用户消息各计一次。记录保存在 `learning_activity`，后续编辑或删除原内容不会改变过去的学习记录。纯浏览、分类/标签修改和模型内部工具消息不计入。

后端启动时，`schema.sql` 创建记录表，并幂等补齐现有笔记、速查卡的创建日和最后更新日、资料上传日、智能体提问日。升级前更早的编辑历史无法从现有时间戳恢复；升级后的每日记录会持续保留。

接口：`GET /api/stats/activity?year=2026`，返回 `year`、`total`、`activeDays`、`max`、`days`（日期和计数）。年份请求失败时，页面显示重试入口；快速切换年份只接受最后一次请求的结果。

验证：前端运行 `node --test src/utils/activityCalendar.test.js` 检查月份对齐、闰年及年界。后端 `LearningActivityIntegrationTest` 使用已迁移的本地 MySQL 并在每个测试结束时回滚；设置 `LEARN_HUB_DB_TESTS=true` 后运行 `mvn -Dtest=LearningActivityIntegrationTest test`，检查同日去重、跨天保留和年度统计范围。常规后端测试默认跳过这些数据库检查。
