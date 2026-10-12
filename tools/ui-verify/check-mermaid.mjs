// 校验 docs/ui-design-2026-10-10.md 里的每个 ```mermaid 代码块能否被 mermaid 解析。
// 用法：node tools/ui-verify/check-mermaid.mjs        （在仓库根目录执行）
//
// 说明：mermaid 的 parse 会调 DOMPurify.sanitize，而 DOMPurify 在 Node 里没有 DOM 时
// 退化成不含 sanitize 的对象。这里用仓库已有的 @mixmark-io/domino（turndown 的依赖）
// 造一个最小 window，在 import mermaid **之前**挂到 globalThis，让 DOMPurify 正常初始化。
// 只影响本校验脚本，不改动仓库代码。
import { readFileSync } from 'node:fs'
import domino from '../../frontend/node_modules/@mixmark-io/domino/lib/index.js'

globalThis.window = domino.createWindow('<html><body></body></html>')
globalThis.document = globalThis.window.document
// Node 24 的 globalThis.navigator 是只读 getter，跳过（DOMPurify 只需要 document）

const { default: mermaid } = await import('../../frontend/node_modules/mermaid/dist/mermaid.esm.mjs')

const doc = readFileSync('docs/ui-design-2026-10-10.md', 'utf8')
// 文档是 CRLF，正则要容忍 \r\n
const blocks = [...doc.matchAll(/```mermaid\r?\n([\s\S]*?)```/g)].map(m => m[1].replace(/\r\n/g, '\n').trim())
if (!blocks.length) {
  console.error('没有找到 mermaid 代码块')
  process.exit(1)
}

mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' })
let failed = 0
for (const [i, src] of blocks.entries()) {
  const label = `block#${i + 1} (${src.split('\n')[0]})`
  try {
    const ok = await mermaid.parse(src)
    if (ok === false) throw new Error('parse returned false')
    console.log(`PASS ${label}`)
  } catch (e) {
    failed++
    console.error(`FAIL ${label}: ${e?.message || e}`)
  }
}
console.log(`\n${blocks.length - failed}/${blocks.length} 个 mermaid 块解析通过`)
process.exit(failed ? 1 : 0)
