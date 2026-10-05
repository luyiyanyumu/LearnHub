/**
 * 笔记分节：「分节融入」的地基。
 *
 * <h3>为什么需要它</h3>
 * 「融入当前笔记」原来只有一条路：让模型读整篇、**输出整篇**。这在长笔记上必然撞输出上限
 * （3.6 万字的正文光输出就要两万多 token），所以后端加了一道 16000 字的闸门，长笔记直接
 * 融不进去。但"放哪儿"这件事需要看整篇（**输入**），"写什么"只需要落到**那一节**（**输出**）——
 * 把输出的范围从"整篇"缩到"一节"，闸门就没有必要了。
 *
 * 于是流程拆成三步，这个文件负责第 1 步和第 3 步（纯函数，可测）：
 * <ol>
 *   <li>{@link splitNoteSections} 把正文切成节，{@link buildNoteOutline} 生成喂给模型的"地图"；</li>
 *   <li>模型看着地图选一节（后端接口），再只改写那一节（后端接口）；</li>
 *   <li>{@link applySectionPatch} 把改写结果贴回正文 —— 只动那一节的字节，其余部分**根本没进过
 *       模型的输出**，因此不可能被静默吃掉。这正是它比"整篇重写"更安全的地方：整篇重写时
 *       模型漏掉一节、少写一段，你从预览里看不出来（正文看起来是完整的）。</li>
 * </ol>
 *
 * <h3>围栏安全是硬要求</h3>
 * 代码块里的 `## xxx`（Python 注释、shell 提示、diff 片段）**不是标题**。这条如果错了，
 * 分节会从代码块中间切开，融入就会把代码块的另一半换掉。所以扫标题时必须跟踪
 * ``` / ~~~ 围栏的开合，且只认行首 0~3 个空格的标题。
 */

/** 围栏行：最多 3 个前导空格 + 三个以上 ` 或 ~（后面可跟语言标记） */
const FENCE_RE = /^ {0,3}(`{3,}|~{3,})(.*)$/

/** ATX 标题：`## 名称`（允许结尾有装饰性的 #） */
const HEADING_RE = /^(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$/

/** 单节超过这个字数就不再整节重写，退化成"只新增子小节"（见 REFERENCE） */
export const SECTION_REWRITE_MAX = 8000

/**
 * 扫出**不在围栏里**的标题行。
 * 返回的偏移量都按 `md` 的字符下标算（不是行号），贴回补丁时直接用。
 */
export function scanHeadings(md) {
  const text = md == null ? '' : String(md)
  const heads = []
  let fence = null
  let offset = 0
  for (const raw of text.split('\n')) {
    // 逐行匹配时先摘掉行尾的 \r：JS 的 `.` 不匹配 \r，CRLF 正文否则一条标题都认不出来。
    // 偏移量仍然用 raw 的长度算，补丁才会落在原文的正确位置上。
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    const f = FENCE_RE.exec(line)
    if (f) {
      const marker = f[1][0]
      if (!fence) fence = marker
      else if (fence === marker) fence = null
    } else if (!fence) {
      const m = HEADING_RE.exec(line)
      if (m) {
        heads.push({
          level: m[1].length,
          heading: m[2].trim(),
          start: offset,
          // 标题行结束（含换行）——正文从这里开始
          end: offset + raw.length + 1,
          lineLength: raw.length,
        })
      }
    }
    offset += raw.length + 1
  }
  return heads
}

/**
 * 判断"哪一层的标题算节"。
 *
 * 不能简单取最浅的一层：笔记正文通常以 `# 标题` 开头（标题其实由 note.title 承载，
 * 但历史正文里留了一行），若把 H1 当节，整篇就变成"一节"，闸门又回来了。
 * 所以优先用 `##`；没有 `##` 时取"数量 ≥ 2 的最浅层"（真正的多节结构）；
 * 再没有就退回最浅层（只有一层标题，那就以它分节）。
 */
export function pickSectionLevel(heads) {
  if (!heads.length) return null
  if (heads.some((h) => h.level === 2)) return 2
  const counts = new Map()
  heads.forEach((h) => counts.set(h.level, (counts.get(h.level) || 0) + 1))
  const levels = [...counts.keys()].sort((a, b) => a - b)
  return levels.find((l) => counts.get(l) >= 2) ?? levels[0]
}

/**
 * 把正文按标题切成节。
 *
 * @returns {{
 *   level: number|null,      节所用的标题层级；null = 这篇没有可用标题
 *   preamble: string,        第一个节标题之前的内容（通常只有 `# 标题` 行）
 *   sections: Array<{index, heading, level, start, end, bodyStart, text, body, size}>
 * }}
 *   `start`~`end` 是这一节在原文里的**精确区间**（标题行到最后一个非空白字符），
 *   `text` 就是这段原文、`body` 是不含标题行的正文 —— 贴补丁只动 [bodyStart, end)。
 */
export function splitNoteSections(md) {
  const text = md == null ? '' : String(md)
  const heads = scanHeadings(text)
  const level = pickSectionLevel(heads)
  if (level == null) return { level: null, preamble: text, sections: [] }

  const tops = heads.filter((h) => h.level === level)
  const sections = tops.map((h, i) => {
    const next = tops[i + 1]
    const rawEnd = next ? next.start : text.length
    // 尾部的空行留给"下一节之前的间隔"，不算进本节，免得补丁把间隔吃掉
    const raw = text.slice(h.start, rawEnd)
    const trimmed = raw.replace(/\s+$/, '')
    const end = h.start + trimmed.length
    const bodyStart = Math.min(h.end, end)
    return {
      index: i + 1,
      heading: h.heading,
      level,
      start: h.start,
      end,
      bodyStart,
      text: text.slice(h.start, end),
      body: text.slice(bodyStart, end),
      size: end - h.start,
    }
  })
  return { level, preamble: text.slice(0, tops[0].start).replace(/\s+$/, ''), sections }
}

/** 一节的开头几句（给模型判断主题用）：取第一个非空行，压成一行、截断 */
function teaser(body, max = 64) {
  const line = String(body || '')
    .split('\n')
    .map((s) => s.trim())
    .find((s) => s && !s.startsWith('```') && !s.startsWith('|'))
  if (!line) return ''
  const flat = line.replace(/\s+/g, ' ')
  return flat.length > max ? flat.slice(0, max) + '…' : flat
}

/** 节内出现的下一级标题名（最多 6 个），让模型看得出这一节里都有什么 */
function childHeadings(section, max = 6) {
  const heads = scanHeadings(section.body).filter((h) => h.level > section.level)
  return heads.slice(0, max).map((h) => h.heading)
}

/**
 * 生成喂给"定位"这一步的笔记地图。
 *
 * 只给大纲（标题 + 字数 + 子标题 + 开头一句），不是正文 —— 定位要看的是结构，
 * 把整篇正文塞进去只会让这一步变贵变慢。编号是**契约**：模型回编号，我们按编号取节，
 * 这样重复标题（两节都叫「其他」）也不会贴错地方。
 */
export function buildNoteOutline(md) {
  const { level, preamble, sections } = splitNoteSections(md)
  if (!sections.length) {
    return {
      level,
      text: preamble
        ? `（这篇笔记没有可用的分节标题，整篇 ${preamble.length} 字）\n开头：${teaser(preamble, 120)}`
        : '（这篇笔记是空的）',
      sections: [],
    }
  }
  const lines = sections.map((s) => {
    const kids = childHeadings(s)
    const parts = [`${s.index}. 【${s.size} 字】${s.heading || '(无标题)'}`]
    if (kids.length) parts.push(`子节：${kids.join(' / ')}`)
    const t = teaser(s.body)
    if (t) parts.push(`开头：${t}`)
    return parts.join('　—　')
  })
  if (preamble) {
    lines.unshift(`（开头前言 ${preamble.length} 字：${teaser(preamble, 60)}）`)
  }
  return { level, text: lines.join('\n'), sections }
}

/**
 * 把"同级或更浅"的标题各降一级。
 *
 * <h3>为什么必须要有这一步</h3>
 * 模型只被要求写**一节**的正文，但它经常自己再写 `## 小标题` 来分段 —— 而 `##` 正是"节"
 * 这一级的标题，写进节内就相当于当场把这一节截成两节。实测（端到端）：让模型给"新增的一节"
 * 写正文，它写了 5 个 `##`，那一节在正文里裂成了 6 节，`## 一、封装…` 直接变成了笔记的兄弟节。
 *
 * 降一级是**无损的机械修法**：内容一字不动，只是把层级落回节内部。
 * 与 `AgentService.stripLeadingSectionHeading`（长文拼装时模型多写一遍小节标题）是同一类补丁 ——
 * 提示词负责让它别写，代码负责写了也不出事。
 *
 * @param {number} sectionLevel 这一节的层级；正文里 ≤ 它的标题都会被降一级
 */
export function demoteSectionHeadings(md, sectionLevel) {
  const text = md == null ? '' : String(md)
  const level = Number(sectionLevel)
  if (!Number.isFinite(level) || level < 1 || level >= 6) return text
  const out = []
  let fence = null
  for (const raw of text.split('\n')) {
    const hasCr = raw.endsWith('\r')
    const line = hasCr ? raw.slice(0, -1) : raw
    const f = FENCE_RE.exec(line)
    if (f) {
      const marker = f[1][0]
      if (!fence) fence = marker
      else if (fence === marker) fence = null
      out.push(raw)
      continue
    }
    const m = fence ? null : HEADING_RE.exec(line)
    // 代码块里的 `## ` 不是标题（同 scanHeadings 的理由）；6 级标题已到底，不再降
    if (m && m[1].length <= level && m[1].length < 6) out.push('#' + raw)
    else out.push(raw)
  }
  return out.join('\n')
}

/**
 * 把改好的**正文**（不含标题行）贴回笔记。
 *
 * 贴之前先核对原文：`section.text` 必须仍然等于这一区间的实际内容。用户在模型跑的过程中
 * 改了字、或者切换了笔记，这里就对不上 —— 宁可放弃并报错，也不要把改好的节贴到错的位置
 * （那会把别的内容覆盖掉）。
 *
 * 另外会把正文里"同级或更浅"的标题降一级（见 {@link demoteSectionHeadings}）：
 * 模型写出的 `##` 属于"节"这一级，留在节内会把这一节截断成几节。
 */
export function applySectionPatch(md, section, newBody) {
  const text = md == null ? '' : String(md)
  if (!section || typeof section.bodyStart !== 'number' || typeof section.end !== 'number') {
    throw new Error('分节信息不完整，无法定位要替换的小节')
  }
  if (text.slice(section.start, section.end) !== section.text) {
    throw new Error('笔记内容在融入过程中发生了变化，已放弃本次改动（正文保持原样）')
  }
  const raw = String(newBody == null ? '' : newBody).trim()
  if (!raw) {
    throw new Error('模型没有给出可用的小节正文，已放弃本次改动（正文保持原样）')
  }
  const body = demoteSectionHeadings(raw, section.level)
  // 标题行与正文之间那段空白（通常是一个空行）按**原文**保留：模型的输出不会带它，
  // 直接替换会把 `## 标题` 和正文贴在一起 —— 这个仓库对 Markdown 排版是有洁癖的。
  const lead = /^\s*/.exec(section.body)[0]
  return text.slice(0, section.bodyStart) + lead + body + text.slice(section.end)
}

/** 在某一节末尾追加内容（"只新增子小节"那一档用它） */
export function appendToSection(md, section, block) {
  const add = String(block == null ? '' : block).trim()
  const merged = section.body ? `${section.body}\n\n${add}` : add
  return applySectionPatch(md, section, merged)
}

/** 在笔记末尾新增一节（没有可用标题时的兜底：等价于"追加章节"） */
export function appendNoteSection(md, heading, body) {
  const text = String(md == null ? '' : md).replace(/\s+$/, '')
  const h = String(heading || '').trim() || '补充'
  // 新节是 `##`，所以它内部的标题必须更深：模型写的 `##` 会把它截成好几节（实测踩到过）
  const b = demoteSectionHeadings(String(body == null ? '' : body).trim(), 2)
  return `${text}\n\n## ${h}\n\n${b}\n`
}
