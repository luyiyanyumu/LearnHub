/**
 * promptFromMd —— 从上传的 Markdown 文件里识别出「提示词」正文。
 *
 * 用途：设置面板里给「对话提示词」加一个「导入 .md」入口，
 * 用户可以直接把写好的提示词文档丢进来，由这里剥掉说明性文字，只取真正要喂给模型的那段。
 *
 * 注意：润色 / 整理格式的提示词**不再用这条路**（历史上正是一次导入把同一份文档
 * 同时填进了两个框，「整理格式」于是长期跑着润色提示词）。它们现在是技能文件
 * `skills/<id>/SKILL.md`，由后端 SkillService 直接读取，界面上只做只读展示。
 *
 * 识别优先级（mode = 'auto'）：
 *   1. 显式小节：标题为 `## 提示词` / `## 系统提示词` / `## Prompt` / `## 指令` 的小节，
 *      取该标题到下一个同级或更高级标题之间的内容；
 *      若该小节内部还包着一个代码块，则再取那个代码块（提示词常被围栏包起来）。
 *   2. 最长代码块：整份文件里内容最长的围栏代码块（``` 或 ~~~）。
 *   3. 全文：去掉 BOM、去掉首行一级标题（`# 文档名`）后的全部内容。
 *
 * 设计原则：宁可少剥不可错剥 —— 任何一步不成立就回退到下一优先级，
 * 并且把「实际用了哪条规则」返回给调用方展示，让用户能核对到底导入了什么。
 */

const SECTION_RE = /^#{2,3}\s*(系统提示词|提示词|指令|prompt|system\s+prompt)\s*$/i
const HEADING_RE = /^(#{1,6})\s+/

/** 归一化换行与 BOM，统一成 \n */
function normalize(raw) {
  return String(raw || '')
    .replace(/^\uFEFF/, '')
    .replace(/\r\n?/g, '\n')
}

/**
 * 抽出围栏代码块。
 * @returns {Array<{lang:string,text:string}>} 按出现顺序，含语言名与内容
 */
function fences(src) {
  const out = []
  const lines = src.split('\n')
  let open = null
  let buf = []
  for (const line of lines) {
    const m = line.match(/^\s*(`{3,}|~{3,})\s*([\w+#.-]*)\s*$/)
    if (!open) {
      if (m) {
        open = { mark: m[1], lang: m[2] || '' }
        buf = []
      }
      continue
    }
    // 收尾：同字符且长度不短于开围栏
    const close = line.match(/^\s*(`{3,}|~{3,})\s*$/)
    if (close && close[1][0] === open.mark[0] && close[1].length >= open.mark.length) {
      out.push({ lang: open.lang, text: buf.join('\n') })
      open = null
      continue
    }
    buf.push(line)
  }
  // 未闭合的围栏不采纳（宁可回退到全文，也不给出半截内容）
  return out
}

/** 取「## 提示词」类小节的内容 */
function sectionBody(src) {
  const lines = src.split('\n')
  let start = -1
  let level = 0
  for (let i = 0; i < lines.length; i++) {
    if (SECTION_RE.test(lines[i].trim())) {
      start = i
      level = lines[i].match(HEADING_RE)[1].length
      break
    }
  }
  if (start < 0) return null
  const body = []
  for (let i = start + 1; i < lines.length; i++) {
    const h = lines[i].match(HEADING_RE)
    if (h && h[1].length <= level) break
    body.push(lines[i])
  }
  const text = body.join('\n').trim()
  return text || null
}

/** 去掉首行一级标题（文档名），仅用于「全文」回退 */
function withoutDocTitle(src) {
  const lines = src.split('\n')
  if (lines.length && /^#\s+\S/.test(lines[0])) {
    const rest = lines.slice(1).join('\n').replace(/^\n+/, '')
    // 只剩标题时保留原样：宁可把标题当提示词，也不要给出空内容
    if (rest.trim()) return rest
  }
  return src
}

/**
 * 若整份内容「恰好就是一个围栏代码块」，剥掉围栏本身。
 * 不这样做的话，当代码块因太短被阈值规则拒绝、回退到「全文」时，
 * 会把 ``` 标记一起当成提示词导进去（实测踩到）。
 */
function stripOuterFence(src) {
  const m = String(src || '')
    .trim()
    .match(/^(`{3,}|~{3,})\s*[\w+#.-]*\s*\n([\s\S]*?)\n\1\s*$/)
  return m ? m[2].trim() : src
}

/**
 * 从 Markdown 文本里识别提示词。
 * @param {string} raw 文件内容
 * @param {'auto'|'fence'|'whole'} mode 识别方式
 * @returns {{text:string, source:'section'|'fence'|'whole', label:string, chars:number}}
 */
export function extractPromptFromMd(raw, mode = 'auto') {
  const src = normalize(raw)
  const all = fences(src)
  const longest = all.reduce((a, b) => (b.text.trim().length > (a?.text.trim().length || 0) ? b : a), null)
  /** 「全文」回退：去 BOM、去文档标题，并剥掉包裹整份内容的围栏 */
  const wholeText = () => stripOuterFence(withoutDocTitle(src)).trim()

  if (mode === 'fence') {
    const text = (longest?.text || '').trim()
    return wrap(text || wholeText(), text ? 'fence' : 'whole')
  }
  if (mode === 'whole') {
    return wrap(wholeText(), 'whole')
  }

  // auto：先找显式小节，再在小节内部找代码块
  const sec = sectionBody(src)
  if (sec) {
    const inner = fences(sec)
    const innerLongest = inner.reduce((a, b) => (b.text.trim().length > (a?.text.trim().length || 0) ? b : a), null)
    if (innerLongest && innerLongest.text.trim().length >= 30) {
      return wrap(innerLongest.text.trim(), 'section')
    }
    return wrap(sec, 'section')
  }
  if (longest && longest.text.trim().length >= 30) {
    return wrap(longest.text.trim(), 'fence')
  }
  return wrap(wholeText(), 'whole')
}

function wrap(text, source) {
  const label = source === 'section' ? '「提示词」小节' : source === 'fence' ? '最长代码块' : '全文'
  return { text: text || '', source, label, chars: (text || '').length }
}
