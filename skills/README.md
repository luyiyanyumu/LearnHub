# skills · 技能目录

这里的每个子目录就是一个**技能**：一份写给模型的指令 + 它的元数据。
笔记编辑页的「AI 润色」「整理格式」不再从数据库读提示词，而是**直接读这里的文件**。

## 为什么要改成技能

原来两条提示词存在 `app_setting` 表里，在设置面板里粘贴/导入。出过一次事：
`ai.polish_prompt` 和 `ai.format_prompt` **存进了同一份 4051 字的润色文档**（导入时选错了框），
于是「整理格式」一直在用润色提示词跑，而界面上两个框都显示「已自定义」，完全看不出问题。

改成文件之后：能进版本库、能 diff、能 code review；一条技能只对应一个用途，不存在"串框"；
长度下界这种与提示词强耦合的参数跟着提示词一起走，不用再记住"改这里必须同步改 Java 常量"。

## 目录结构

```
skills/
  markdown-polish/
    SKILL.md       ← 送进模型的提示词（frontmatter 之外的全部内容）
    REFERENCE.md   ← 给人看的参考：设计论证、自检清单（不会送进模型）
  markdown-beautify/
    SKILL.md
    REFERENCE.md
  note-merge/
    SKILL.md       ← 把回答按结构融入当前笔记（并进对应小节 / 新增合适小节）
    REFERENCE.md   ← 设计取舍：为什么不是"追加到文末"、长度闸门、验收要点
  paper-finder/
    SKILL.md       ← 文献检索：找论文、给网址与下载入口，禁止编造参考文献
    REFERENCE.md   ← 数据源清单（Crossref/arXiv/DBLP/OpenAlex/Unpaywall…）、标识符校验、常见错误
  wiki-schema/
    SKILL.md
```

**SKILL.md 的正文就是提示词本身**，不要在里面写"给人看"的说明——那会被一起发给模型。
人看的材料放 `REFERENCE.md`（同目录，任意文件名，只要不是 `SKILL.md`）。

## 现有技能一览

| 技能 | 用途 | 绑定的按钮 |
| --- | --- | --- |
| `markdown-polish` | 笔记润色：修错别字语病、统一术语标点，事实与结构不变 | AI 润色 |
| `markdown-beautify` | 整理格式：按渲染能力白名单重排 Markdown | 整理格式 |
| `wiki-schema` | LLM wiki 页面的结构规范 | 无（由 wiki 生成流程使用） |
| `note-merge` | 把智能体的回答**按结构融入**当前笔记（并进对应小节 / 新增合适小节），产出完整新正文 | 无（由笔记页的「融入当前笔记」使用） |
| `paper-finder` | 文献检索：按主题/标题/作者找论文，给出可核对的官方页与下载入口；**任何参考文献都必须来自本次实际抓取到的页面，禁止凭记忆编造**；用户要留档时可经 MCP 工具把全文直链直接存进资料库 | 无（由支持技能加载的智能体按需取用） |

`paper-finder` 的硬约束写在 `SKILL.md` 第零节：每条结果的标题/作者/年份/发表处/DOI/arXiv/链接
都要能指认到本次抓取过的页面，并附「证据」；查不到就写「未找到」。相关的真实数据源与调用模板、
以及「找到论文 → 一键入库 → 验证能检索」的工具用法在 `paper-finder/REFERENCE.md`。

## frontmatter 字段

```yaml
---
name: markdown-polish          # 技能名（与目录名一致，界面展示用）
description: 一句话说明…        # 界面展示用，也便于别的 agent 判断何时该用它
applies_to: polish             # polish | format | 其他；标明它服务于哪个按钮，仅作说明与展示
min_ratio: 0.6                 # 可选：输出长度下限比例，直接作为后端长度守卫的阈值
---
```

只解析这种扁平的 `key: value`，不引 YAML 库（够用、零依赖，也不会因为缩进写错而静默失效）。

## 后端怎么找这些文件

按顺序探测，第一个存在且可读的目录生效：

1. 配置项 `learnhub.skills-dir`（`application.yml` 或 `--learnhub.skills-dir=...`）
2. 环境变量 `LEARNHUB_SKILLS_DIR`
3. `./skills`（从仓库根目录启动后端时）
4. `../skills`（从 `backend/` 目录启动时，**README 的启动方式是这种**）
5. `../../skills`

启动时会打印实际生效的目录与技能清单；找不到时「润色/整理格式」会报出明确的错误，
而不是静默换成别的提示词。

## 实时生效

每次点「润色 / 整理格式」都重新读盘，**改完存盘即生效，不用重启后端**。
（代价是每次多一次 4KB 读盘，相对秒级的模型调用可以忽略。）

## 两种修改方式

1. **直接改文件**（推荐）：编辑 `SKILL.md` 正文，存盘即生效。
2. **在设置 → 技能里上传**：点某个技能的「替换」，或点「上传技能」新建 ——
   选好的 `.md` / `.txt` 会被写成 `skills/<id>/SKILL.md`。

上传时的规则：

- 文件**不带 frontmatter** → 只替换正文，原有元数据（`name` / `description` / `applies_to` / `min_ratio`）**原样保留**；
- 文件**自带 frontmatter** → 连元数据一起替换（想改 `min_ratio` 就用这种方式）；
- 识别方式默认「整体导入」（技能文件本身就含 frontmatter，不能再剥一层）；
  如果上传的是老那种"带说明文字的提示词文档"，可切到「自动识别」让它只取提示词那一段；
- 覆盖前旧版本会备份到 `skills/.history/<id>/SKILL-<时间戳>.md`（已 gitignore，不进版本库）；
- 技能名只允许小写字母、数字、`.`、`_`、`-`，且以字母或数字开头。

## 新增一个技能

在 `skills/` 下建目录、写 `SKILL.md` 即可，也可以在**设置 → 技能 → 上传技能**里直接建
（填技能名 + 选文件）。设置面板的「技能」列表会自动列出新技能。
要让某个按钮用它，把对应按钮的 `applies_to` 写清楚并在 `AgentService` 里改映射；
目前映射是固定的两个 id：润色 → `markdown-polish`，整理格式 → `markdown-beautify`。

## 改提示词前的自检

- 两个技能的规则**不要互相混用**（一个允许克制缩写、一个不做篇幅约束，混用会让模型收到矛盾指令）；
- 改了 `SKILL.md` 里的长度下界，要同步改 frontmatter `min_ratio`，否则守卫会按旧阈值把合规结果判成"缩水"并静默回退原文；
- `markdown-beautify` 里的语法白/黑名单是根据本工程真实渲染能力写的，改之前先看 `REFERENCE.md` 的「往返安全」一节。
