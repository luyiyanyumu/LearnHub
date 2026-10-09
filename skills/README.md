# learn-hub 技能目录

每个子目录提供一项技能：`SKILL.md` 包含元数据和发送给模型的提示词，`REFERENCE.md` 提供维护说明。文件方式便于版本管理、审阅和维护；笔记润色、格式整理及内容融入等功能直接读取对应技能。

## 技能一览

| 目录 | 用途 | 使用入口 |
| --- | --- | --- |
| [markdown-polish](markdown-polish/SKILL.md) | 修正错别字、语病和术语标点，保持事实与结构 | 笔记页「AI 润色」 |
| [markdown-beautify](markdown-beautify/SKILL.md) | 按渲染能力整理 Markdown 格式 | 笔记页「整理格式」 |
| [note-merge](note-merge/SKILL.md) | 将新内容融入对应小节或新增合适小节，输出完整正文 | 「融入当前笔记」的短笔记流程 |
| [note-merge-locate](note-merge-locate/SKILL.md) | 根据长笔记大纲定位融入位置，输出 JSON | 长笔记分节融入第一步 |
| [note-merge-section](note-merge-section/SKILL.md) | 改写指定小节；小节过大时生成新增内容块 | 长笔记分节融入第二步 |
| [wiki-schema](wiki-schema/SKILL.md) | 约束 wiki 页面的结构和生成行为 | wiki 生成流程 |
| [paper-finder](paper-finder/SKILL.md) | 检索论文，提供可核对的来源页与下载入口 | 支持技能加载的智能体 |
| [word-doc](word-doc/SKILL.md) | 调用 `create_word_document` 生成 Word 文档并返回下载链接 | 支持该工具的智能体 |

`paper-finder` 要求每条文献的标题、作者、年份、标识符和链接都有本次检索的页面证据，查不到的信息明确标为未找到。数据源和入库流程见其 [REFERENCE.md](paper-finder/REFERENCE.md)。

## 文件格式

```text
skills/
  <skill-id>/
    SKILL.md
    REFERENCE.md    # 可选，供维护者阅读
```

`SKILL.md` 的 frontmatter 之外的正文会发送给模型，维护说明应放在独立参考文件中。没有 frontmatter 时，整个文件作为提示词正文。

```yaml
---
name: example-skill
description: 技能用途的一句话说明
applies_to: polish
min_ratio: 0.6
---

在这里填写提示词正文。
```

| 字段 | 说明 |
| --- | --- |
| `name` | 展示名称，通常与目录名一致 |
| `description` | 用途说明 |
| `applies_to` | 用途标记，用于展示；不会自动绑定按钮 |
| `min_ratio` | 可选，供相关流程的输出长度守卫使用 |

后端只解析扁平的 `key: value` 元数据，不支持完整 YAML 的嵌套结构。字段值应写在同一行。

## 目录发现与加载

后端按以下顺序探测，选择第一个包含子目录 `SKILL.md` 文件的技能目录：

1. 配置项 `learnhub.skills-dir`。
2. 环境变量 `LEARNHUB_SKILLS_DIR`。
3. 工作目录下的 `./skills`。
4. 工作目录上一级的 `../skills`。
5. 工作目录上两级的 `../../skills`。

例如，从 `backend/` 启动时，可自动发现仓库的 `../skills`。自定义部署目录时，显式设置 `learnhub.skills-dir` 或 `LEARNHUB_SKILLS_DIR`。

调用技能时重新读取文件，正文修改保存后即可用于后续请求。技能目录在后端启动时确定，修改目录配置后需重启后端。润色、格式整理等流程在目录或技能文件缺失时会报错；wiki 生成在缺少 `wiki-schema` 时使用内置规则。

## 修改与上传

可直接编辑技能文件，也可在「设置 → 技能」中上传或替换：

- 上传内容不带 frontmatter：替换正文，保留原有元数据。
- 上传内容带 frontmatter：提供的元数据字段会覆盖原值；未提供的字段保留原值。
- 显式填写的元数据覆盖项优先于文件内和原有元数据。
- 上传完整技能文件时使用「整体导入」；带额外说明的提示词文档可选择「自动识别」。
- 覆盖前备份到 `skills/.history/<skill-id>/SKILL-<timestamp>.md`，历史备份不进入版本库。
- 目录名以小写字母或数字开头，可包含小写字母、数字、`.`、`_`、`-`，长度最多 64 个字符。

## 新增技能

在技能目录下创建 `<skill-id>/SKILL.md`，或使用「设置 → 技能 → 上传技能」。新技能会出现在技能清单中。

应用功能的技能映射由后端代码决定。当前润色使用 `markdown-polish`，格式整理使用 `markdown-beautify`；需要让已有按钮使用新技能时，同步调整 [SkillService](../backend/src/main/java/org/dyh/learnhub/service/SkillService.java) 和调用方映射。

## 维护约定

- 提示词规则与用途保持一致，避免混入其他技能的篇幅或格式要求。
- 提示词中的长度要求与 frontmatter `min_ratio` 保持一致。
- 修改 Markdown 语法范围前，检查对应 `REFERENCE.md` 和前端渲染能力。
- 通用技能不写死用户的行业背景、个人偏好或本机知识库内容。
- 示例使用通用内容和占位路径，不写入真实用户目录、业务资料或运行记录。
