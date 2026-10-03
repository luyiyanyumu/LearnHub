# 笔记定向编辑工具

在笔记页打开智能体，可以直接说：

- “给当前笔记添加目录，只显示二级到三级标题。”
- “删掉第 2 处‘其实’，其他文字保留。”
- “把‘这是我的知识笔记’里的‘的’删除。”
- “把 Python 中的 t 加粗，并把‘重点’设为红色。”
- “在‘注意事项’前插入一段说明。”

智能体先用 `get_note` 读取正文和 `content_hash`，再调用 `edit_note`。所有局部改动合成一次调用，卡片显示每项改动前后的片段，确认后写入并同步当前编辑器。当前笔记有未保存内容时先保存；同一文本有多个匹配时必须指定第几处或前后文，工具不会自动选第一处。

## 参数示例

```json
{
  "note_id": 12,
  "expected_hash": "从 get_note 返回的 content_hash 原样复制",
  "operations": [
    { "action": "delete", "text": "其实", "occurrence": 2 },
    { "action": "format", "text": "t", "prefix": "Py", "suffix": "hon", "style": "bold" },
    { "action": "insert_toc", "min_level": 2, "max_level": 3 }
  ]
}
```

支持 `replace`、`delete`、`insert_before`、`insert_after`、`format`、`insert_toc`。用 `occurrence`（从 1 开始）选择某处，或用紧邻的 `prefix` / `suffix` 定位。只有明确要修改所有匹配时才传 `all: true`。操作依次作用于上一步结果，后续操作的定位文本也要按修改后的原文填写。

`format` 支持加粗、斜体、下划线、删除线、行内代码、文字颜色、背景色、字号、上标和下标。文字颜色/背景色的 `value` 使用 `#RGB`、`#RRGGBB` 或 `red` 等英文颜色名；字号使用 `8`～`72` 的整数文本（px）。格式目标只接受行内正文文字，代码块、链接地址、HTML 属性和 Markdown 结构不能作为格式目标。已有 HTML 正文里的单个字也可以定位；原有文字和非目标内容保留。

目录默认位于正文开头，也可用 `placement: end` 放到末尾，或用 `before` / `after` 和 `text` 定位。目录采用与预览、HTML 导出一致的标题锚点，支持同名标题、Setext 标题，忽略代码块和 HTML 注释中的伪标题。工具生成的目录带隐藏标记，再次调用会更新它；手写目录不会自动覆盖。

## 接口与 MCP

- `GET /api/notes/{id}`：详情带 `contentHash`。
- `POST /api/notes/{id}/edit/preview`：请求只传 `expected_hash`、`operations`；返回改动片段，不写入。
- `POST /api/notes/{id}/edit`：确认后执行同样的操作与版本。正文变化时拒绝写入，任一操作失败时整批不写入。
- MCP `get_note` 返回 `content_hash`；`edit_note` 采用同一套参数，并增加 `dry_run`，默认 `true` 只预览，检查后传 `false` 执行。

一次最多 50 项操作。返回 `beforeHash`、`afterHash`、`changed` 和每项的命中数量/前后片段；长片段会明确标记截取。写入只更新正文、摘要和更新时间，并沿用学习记录与 Wiki 的内容变更通知；标题、分类、标签保持原样。
