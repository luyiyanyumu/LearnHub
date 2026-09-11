/**
 * 修复粘贴内容里的「排版污染」（语雀 / Word / 公众号等富文本平台常见）：
 *
 * 0. 语雀默认色：语雀复制出的正文常被包成
 *    `<span style="color: rgb(38, 38, 38)">…</span>`（就是它自己的默认字色），
 *    属于纯噪音。规则：整条 style 只有默认色 → 连标签一起剥掉；
 *    还有别的声明 → 只摘掉 color 声明，保留字号/背景等真实格式。
 *
 * 1. HTML 标签内的智能引号：`<font style=”color:red”>ls</font>` 属性引号被写成
 *    中文弯引号 ” —— markdown-it（html:true）认不出这是合法标签，预览里就会
 *    原样显示 <font ...> 源码。
 *    规则：只在 <tag ...> 形式的标签内部把 “ ” 换成 "、‘ ’ 换成 '；
 *    正文里的中文引号（“”）是正常标点，一律不动。
 *
 * 2. 星号与字体标签混排：`****<font>X</font>** **<font>Y</font>**` 里星号数量
 *    不规范（四连星等），CommonMark 强调配对失败，预览漏出字面 **。
 *    规则：`**…**`（含 3+ 连星）紧包 <font…>…</font> 的，整体改写成
 *    <strong>…</strong>，语义（加粗+颜色）不变、配对必然成功。
 *
 * 3. 行内代码里包着字体标签：`` `<font …>mkdir</font>` `` —— 行内代码不解析
 *    HTML，预览会把整个标签原样显示（语雀粘贴 + 后期编辑混用的产物）。
 *    规则：去掉反引号本身，`<font>mkdir</font>` → <font>mkdir</font>，
 *    命令恢复成和未包反引号的命令一致的彩色样式。
 *
 * 4. 孤立星号行：整行只有 1~2 个星号（外部工具转换残渣），预览会漏出字面 **，
 *    整行删除。注意 3 个以上（`***` / `****`）是合法的 <hr> 分隔线，保留不动。
 * 5. 不成对的 <strong> 标签：行内多余的 </strong> 闭合直接丢弃；
 *    行内多余的 <strong> 开标签，在行尾补足闭合（保留加粗意图）。
 *
 * 说明：`font` 与 `span` 一视同仁（语雀用 span，md-editor 默认插 font），
 * 所有标签规则都写成 `(?:font|span)` 并用反向引用保证开闭标签同名。
 *
 * 通用规则：
 * 1) ``` / ~~~ 围栏代码块整体跳过。逐行检测围栏开合，并**连长度一起比**：
 *    4 反引号围栏里的 3 反引号不算闭合，否则会被误判成「围栏结束」，
 *    之后正文漏清洗 / 代码被当正文清洗。
 * 2) 行内代码默认整体锁定（换成占位符再过规则），保证代码内容逐字不变；
 *    只有「内部是带属性的 font/span 标签」的行内代码例外，它要走第 3 条规则。
 * 3) 整行只有 1~2 个星号才删除；3 个以上是合法 <hr>，保留。
 */

/** 语雀默认正文色（这些值等价于「没设颜色」，剥掉更干净） */
const NOISE_DECL = [
  /^color\s*:\s*(?:rgb\(\s*38\s*,\s*38\s*,\s*38\s*\)|#262626|inherit|initial|unset)$/i,
  /^background(?:-color)?\s*:\s*(?:transparent|none|inherit|initial|unset)$/i,
  /^font-family\s*:\s*inherit$/i,
]
const isNoiseDecl = (d) => NOISE_DECL.some((re) => re.test(d))

/** 标签对：font 或 span，开闭同名 */
const TAG = '(font|span)'
const CLOSE = '<\\/\\1>'

/** 行内代码片段：`…` / ``…`` */
const CODE_SPAN = /(`+)([^`]*?)\1/g
/** 占位符：用一个正文里不可能出现的控制字符包住序号，收尾再还原 */
const PH = '\u0001'
/**
 * 「需要走去反引号规则」的行内代码特征：内部是**带属性的** font/span 标签。
 * 这是语雀粘贴 + 后期编辑混用的典型产物，例如 `` `<font style="color:red">ls</font>` ``，
 * 要去掉反引号让命令恢复成彩色样式。
 * 注意必须是「标签名后面跟空白」才算带属性——`` `<span>x</span>` `` 这种没有属性的
 * 不能算，否则它会被 0c 步脱壳成 `` `x` ``，静默改掉用户写在行内代码里的 HTML 示例。
 */
const NEEDS_DEBACKTICK = /<(?:font|span)\s[^>]*>/i

/** 把不该被 HTML 清洗规则碰到的行内代码换成占位符 */
function lockInlineCode(line) {
  const items = []
  const text = line.replace(CODE_SPAN, (whole, ticks, inner) => {
    if (NEEDS_DEBACKTICK.test(inner)) return whole
    items.push(whole)
    return PH + (items.length - 1) + PH
  })
  return { text, items }
}

/** 还原被 lockInlineCode 保护起来的行内代码 */
function unlockInlineCode(line, items) {
  if (!items.length) return line
  return line.replace(
    new RegExp(PH + '(\\d+)' + PH, 'g'),
    (whole, idx) => items[Number(idx)] ?? whole,
  )
}

export function fixHtmlQuotes(md = '') {
  const lines = String(md).split('\n')
  // 围栏状态：null = 正文；否则记录「开围栏那串标记」（例如 "```" / "~~~~"）。
  // 必须连长度一起记，否则 4 反引号围栏内部出现的 3 反引号会被误当成闭合线，
  // 之后整段正文就被当成代码跳过清洗（或者反过来把代码当正文乱改）。
  let fence = null
  for (let i = 0; i < lines.length; i++) {
    const fenceMark = lines[i].match(/^\s*(`{3,}|~{3,})/)
    if (fence === null) {
      if (fenceMark) {
        fence = fenceMark[1]
        continue
      }
    } else {
      // 闭合线：同一种字符、且不短于开围栏
      if (fenceMark && fenceMark[1][0] === fence[0] && fenceMark[1].length >= fence.length) {
        fence = null
      }
      continue
    }
    // 孤立星号行 → 整行删除。
    // 但只删 1~2 个星号的行：`**` 在 CommonMark 里不是合法分隔线，会原样漏出字面星号；
    // 3 个以上（`***` / `****`）是合法的 <hr>，必须保留
    // —— 以前是一律删掉，会把用户手写的分隔线静默吃掉。
    if (/^\s*\*{1,2}\s*$/.test(lines[i])) {
      lines[i] = null
      continue
    }
    // 先锁住「不参与清洗」的行内代码，避免下面的 HTML 规则改到代码内容
    const { text, items } = lockInlineCode(lines[i])
    let s = text
      // 0a) 整条 style 只有默认色 → 连标签一起剥掉
      .replace(
        new RegExp(
          `<${TAG}\\s+style="\\s*color:\\s*(?:rgb\\(\\s*38\\s*,\\s*38\\s*,\\s*38\\s*\\)|#262626)\\s*;?\\s*"[^>]*>([\\s\\S]*?)${CLOSE}`,
          'gi',
        ),
        '$2',
      )
      // 0b) 还有别的声明 → 只摘掉默认色那一段，保留字号/背景等真实格式
      .replace(/(<(?:font|span)\b[^>]*\bstyle=")([^"]*)("[^>]*>)/gi, (m, pre, style, post) => {
        const kept = style
          .split(';')
          .map((d) => d.trim())
          .filter(Boolean)
          .filter((d) => !isNoiseDecl(d))
        return pre + kept.join('; ') + post
      })
      // 0c) style 被清空的标签：去掉空属性；无属性的 span/font 直接脱壳
      .replace(/\sstyle=""/gi, '')
      .replace(/<(?:span|font)>([^<]*)<\/(?:span|font)>/gi, '$1')
      // 1) 标签内弯引号 → 直引号
      .replace(/<[/a-zA-Z][^<>]*>/g, (tag) => tag.replace(/[“”]/g, '"').replace(/[‘’]/g, "'"))
      // 2) 行内代码包着 font/span 标签 → 去掉反引号（先于星号规则，避免误改代码段内容）
      .replace(/(`+)([^`]*?)\1/g, (whole, ticks, inner) =>
        /<(?:font|span)\b/.test(inner) ? inner : whole
      )
      // 3a) 空标签（<font…></font>，渲染为空、纯属残渣）连同紧贴的星号一起删除：
      //     `：****<font></font>**` → `：**`，避免四连星被空标签抢走闭合导致前文漏星号
      .replace(new RegExp(`\\*{2}<${TAG}[^>]*>\\s*${CLOSE}(?:\\*\\*)?`, 'g'), '')
      // 3b) 文字后 3+ 连星紧接标签：星号串是「前文加粗闭合 + 标签开启」两半，
      //     拆开改写：`：****<font>X</font>**` → `：**<strong><font>X</font></strong>`
      .replace(
        new RegExp(`([^>\\s*])\\*{4,}(<${TAG}[^>]*>(?:(?!${CLOSE}).)*${CLOSE})\\*{2}`, 'g'),
        '$1**<strong>$2</strong>',
      )
      // 3c) 常规：** 包标签 → <strong> 包标签
      .replace(new RegExp(`\\*{2,}(<${TAG}[^>]*>.*?${CLOSE})\\*{2,}`, 'g'), '<strong>$1</strong>')
    // 4) 平衡 <strong> 标签（处理外部工具留下的不成对残渣）
    lines[i] = unlockInlineCode(balanceStrong(s), items)
  }
  return lines.filter((l) => l !== null).join('\n')
}

/** 行内 <strong> 不成对时：多余 </strong> 丢弃；多余 <strong> 在行尾补闭合 */
function balanceStrong(line) {
  if (!line.includes('<strong')) return line
  let opens = 0
  let out = ''
  let i = 0
  while (i < line.length) {
    if (line.startsWith('</strong>', i)) {
      if (opens > 0) {
        out += '</strong>'
        opens--
      }
      i += 9
      continue
    }
    if (/^<strong[ >]/.test(line.slice(i, i + 9))) {
      const gt = line.indexOf('>', i)
      if (gt === -1) {
        out += line.slice(i)
        i = line.length
        continue
      }
      out += line.slice(i, gt + 1)
      opens++
      i = gt + 1
      continue
    }
    out += line[i]
    i++
  }
  return out + '</strong>'.repeat(opens)
}
