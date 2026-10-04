/**
 * break-ui 的"最坏数据集"—— 只给 `?data=` 用，**生产路径不会加载**（FileLibrary 里是动态 import）。
 *
 * 每个值都满足两条之一（技能的硬要求）：
 *  ① 真实世界里会出现的形态（相机文件名、CJK 长名、emoji 开头、法务审批版文件名…）；
 *  ② 后端/数据库**真实接受的上限**：`file_info.origin_name VARCHAR(255)`、`summary VARCHAR(500)`。
 * 不用 "aaaa…" 那种无意义填充 —— 那种一拿出来就会被合理驳回。
 *
 * 失败点**刻意分散在不同行**（而不是全堆在第 1 行），因为真实数据就是这样：
 * 第 1 行是长文件名、第 2 行是不能断行的长串、第 3 行是中文、第 4 行是 emoji……
 */

/** 数据库上限：origin_name VARCHAR(255) */
const NAME_LIMIT = 255

const longApproved = 'Q3 Board Deck — FINAL (revised) v12 [approved by legal] 已通过法务复核.pdf'
const cameraName = 'IMG_20250914_183022_HDR_portrait_edited_edited_final_v2.HEIC'
/** 顶到列上限的名字：真实的人真会拖这么长的文件名（多段版本号 + 客户名 + 日期） */
const atLimit = ('Northwind Industries Holdings — 2026 年度供应商准入与合规复核材料（第三版，含附件 A/B/C 与法务批注）'
  + '— final-final-REALLY-final v27 (1).xlsx').padEnd(NAME_LIMIT, '（附件）').slice(0, NAME_LIMIT)

const longCjk = '机器学习模型部署与推理优化实践笔记（含量化、蒸馏、批处理与显存占用实测数据汇总）.docx'
const longSummary = ('这份资料讲的是向量检索在个人知识库里的落地：为什么传统数据库的"精确匹配/范围查询"'
  + '解决不了"找与这段意思最接近的十条记录"，Milvus 的集合/分区/索引该怎么建，'
  + 'HNSW 与 IVF 的取舍，以及 embedding 维度换了之后为什么必须整套重建索引。'
  + '（这段说明是 500 字上限附近的真实长度，用来验证「说明」列在满字数时会不会把行高撑到失控。）').slice(0, 500)

/** 14 行，覆盖各列边界；textStatus 的六个枚举值全部出现（技能要求"每个状态都在同一份数据里"） */
const ROWS = [
  // 1) 长文件名 + 空格/破折号/括号（可断行机会很多）
  { id: 9001, originName: longApproved, ext: 'pdf', size: 4_512_678, categoryId: 13,
    textStatus: 'ok', textChars: 118_747, summary: '', createdAt: '2026-09-21T03:37:00' },
  // 2) 不能断行的长串（相机文件名，无空格无连字符）
  { id: 9002, originName: cameraName, ext: 'heic', size: 18_446_744, categoryId: null,
    textStatus: 'unsupported', textChars: 0, textError: '这个格式不支持抽正文（heic）',
    summary: '', createdAt: '2026-10-02T11:05:00' },
  // 3) 中文无空格：任意位置都能断
  { id: 9003, originName: longCjk, ext: 'docx', size: 2_099_152, categoryId: 13,
    textStatus: 'ok', textChars: 45_231, summary: longSummary, createdAt: '2026-09-30T08:12:00' },
  // 4) emoji 开头 + 很短的中文名（首字母/截断逻辑容易出问题）
  { id: 9004, originName: '🦊 狐狸笔记.pdf', ext: 'pdf', size: 1024, categoryId: 18,
    textStatus: 'ok', textChars: 1, summary: '', createdAt: '2026-10-01T22:41:00' },
  // 5) 顶到 VARCHAR(255) 上限
  { id: 9005, originName: atLimit, ext: 'xlsx', size: 128_849_018_880, categoryId: 13,
    textStatus: 'ok', textChars: 1_234_567, summary: '', createdAt: '2026-09-25T14:00:00' },
  // 6) 极短名 + 没有扩展名（ext 为 null → 走"其他"分支）
  { id: 9006, originName: 'Jo', ext: null, size: 1, categoryId: null,
    textStatus: 'pending', textChars: 0, summary: '', createdAt: '2026-10-03T09:00:00' },
  // 7) 转义载荷：文件名里带标签与实体（必须原样显示，不能当 HTML）
  { id: 9007, originName: '<script>alert(1)</script> &amp; **bold** <b>html</b>.pdf', ext: 'pdf',
    size: 2048, categoryId: null, textStatus: 'failed', textChars: 0,
    textError: '解析失败：Unexpected token < in JSON at position 0',
    summary: '文件名里带标签与实体，用来验证转义层', createdAt: '2026-10-01T07:20:00' },
  // 8) 文件名里有换行（单行字段收到多行值）
  { id: 9008, originName: 'Line one\nLine two.pdf', ext: 'pdf', size: 4096, categoryId: 13,
    textStatus: 'empty', textChars: 0, summary: '无文字', createdAt: '2026-09-29T16:30:00' },
  // 9) 与第 1 行**同名**（列表里只能靠 id 区分）
  { id: 9009, originName: longApproved, ext: 'pdf', size: 4_512_678, categoryId: 13,
    textStatus: 'ok', textChars: 118_747, summary: '', createdAt: '2026-09-21T03:37:00' },
  // 10) 说明为空 + 状态 skipped
  { id: 9010, originName: '归档.zip', ext: 'zip', size: 734_003_200, categoryId: null,
    textStatus: 'skipped', textChars: 0, textError: '压缩包不抽正文', summary: '', createdAt: '2026-08-14T10:00:00' },
  // 11) 时间边界：1970 / 未来 / 空
  { id: 9011, originName: 'epoch.txt', ext: 'txt', size: 0, categoryId: 18,
    textStatus: 'ok', textChars: 0, summary: '', createdAt: '1970-01-01T00:00:00' },
  { id: 9012, originName: '未来时间戳.md', ext: 'md', size: 512, categoryId: 18,
    textStatus: 'ok', textChars: 42, summary: '', createdAt: '2027-03-01T12:00:00' },
  { id: 9013, originName: '没有时间.pdf', ext: 'pdf', size: null, categoryId: null,
    textStatus: 'ok', textChars: 7, summary: '', createdAt: null },
  // 12) 说明顶到 VARCHAR(500) 上限 + 中文分类名
  { id: 9014, originName: 'Vector-Retrieval-Notes.pdf', ext: 'pdf', size: 3_999_999, categoryId: 18,
    textStatus: 'ok', textChars: 88_888, summary: longSummary, createdAt: '2026-10-04T06:00:00' },
]

/** 1,000 行：列表接口不分页，这是真实的性能与滚动上限 */
function huge() {
  return Array.from({ length: 1000 }, (_, i) => ({
    id: 10_000 + i,
    originName: `批量导入的资料 ${String(i + 1).padStart(4, '0')} — ${i % 7 === 0 ? longApproved : '常规文件名'}.pdf`,
    ext: 'pdf', size: 1_048_576 + i * 977, categoryId: 13,
    textStatus: 'ok', textChars: 5_000 + i, summary: '', createdAt: '2026-09-28T12:00:00',
  }))
}

export const FIXTURE_NAMES = [
  { key: 'worst', label: 'Worst case' },
  { key: 'demo', label: 'Demo data' },
  { key: 'empty', label: 'Empty' },
  { key: 'one', label: 'One' },
  { key: 'huge', label: '1,000 rows' },
]

/**
 * @param {string} key worst / demo / empty / one / huge
 * @returns {any[] | null} demo 时返回 null（表示"走真实接口"）
 */
export function pickFixture(key) {
  switch (key) {
    case 'worst':
      return ROWS
    case 'empty':
      return []
    case 'one':
      return [ROWS[2]]
    case 'huge':
      return huge()
    default:
      return null
  }
}
