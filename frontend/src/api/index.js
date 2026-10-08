import axios from 'axios'
import { ElMessage } from 'element-plus'

// 统一请求实例：/api 在开发环境由 Vite 代理到后端 8080
const request = axios.create({ baseURL: '/api', timeout: 15000 })

request.interceptors.response.use(
  (resp) => {
    // 二进制响应（文件下载）直接透传
    if (resp.data instanceof Blob) {
      return resp
    }
    const body = resp.data
    if (body && body.code === 200) {
      return body.data
    }
    if (!resp.config?.silentError) ElMessage.error((body && body.msg) || '请求失败')
    return Promise.reject(new Error((body && body.msg) || '请求失败'))
  },
  (err) => {
    const data = err.response?.data
    const msg = (data && data.msg) || err.message || '网络错误'
    if (!err.config?.silentError) ElMessage.error(msg)
    // 把后端的可读信息回填到 .message：阅读器翻译、公式识别等直接显示 err.message
    // 的面板会因此看到「翻译调用失败（档案：xxx）」而不是 axios 的英文默认文案
    // （实测：上游 llama.cpp 引擎崩了后端回 500 + 可读 msg，但面板只会显示
    // 「Request failed with status code 500」，完全看不出是哪个档案、为什么挂了）。
    // .response 保留原值，其他走 e?.response?.data?.msg 的页面不受影响。
    err.message = msg
    return Promise.reject(err)
  },
)

export const categoryApi = {
  tree: () => request.get('/categories'),
  add: (data) => request.post('/categories', data),
  update: (id, data) => request.put(`/categories/${id}`, data),
  remove: (id) => request.delete(`/categories/${id}`),
}

export const tagApi = {
  list: () => request.get('/tags'),
  add: (name) => request.post('/tags', { name }),
  rename: (id, name) => request.put(`/tags/${id}`, { name }),
  remove: (id) => request.delete(`/tags/${id}`),
}

export const noteApi = {
  page: (params) => request.get('/notes', { params }),
  detail: (id) => request.get(`/notes/${id}`),
  add: (data) => request.post('/notes', data),
  update: (id, data) => request.put(`/notes/${id}`, data),
  remove: (id) => request.delete(`/notes/${id}`),
}

export const quickRefApi = {
  list: (params) => request.get('/quick-refs', { params }),
  detail: (id) => request.get(`/quick-refs/${id}`),
  add: (data) => request.post('/quick-refs', data),
  update: (id, data) => request.put(`/quick-refs/${id}`, data),
  remove: (id) => request.delete(`/quick-refs/${id}`),
}

export const statsApi = {
  dashboard: () => request.get('/stats'),
  activity: (year) => request.get('/stats/activity', { params: { year } }),
}

export const knowledgeApi = {
  /** 统一检索：{ keyword, total, items:[{type:'note'|'quick_ref', id, title, snippet, categoryName, updatedAt}] }；kw 空则返回最近知识 */
  // The knowledge page owns latest-request errors; other callers keep the global toast.
  search: (kw, { silentError = false } = {}) => request.get('/knowledge/search', { params: { kw }, silentError }),
}

// AI 相关请求的统一超时：思考模式（DeepSeek V4 / Kimi K3 / GLM-5.3 等）会让长文处理
// 比 90 秒慢得多（实测 4863 字润色 81.6s，更长文档直接破 90s），而对齐后端 HttpClient 的 300s。
const AI_TIMEOUT = 300000

/**
 * 流式润色 / 整理格式：边处理边回报分段进度。
 *
 * 为什么不用 axios：要逐段读响应流。而 axios 的响应拦截器做的是「一次性 json 解包 + 统一报错」，
 * 对 SSE 不适用，所以这里用 fetch 手工读流，并**自行复刻拦截器的报错文案**
 * （err.response.data.msg → err.message → '网络错误'），保证错误提示风格与其他接口一致。
 *
 * @param {{text:string, mode:'polish'|'format'}} data
 * @param {(event:string, payload:object)=>void} [onEvent] 事件回调：progress / done / failed
 * @param {AbortSignal} [signal] 用于「取消处理」
 * @returns {Promise<string>} 处理后的 Markdown
 */
async function polishStream(data, onEvent, signal) {
  return streamPost('/api/ai/polish-stream', data, onEvent, signal)
}

/**
 * 把智能体的回答**融入当前笔记**（SSE）。
 *
 * 与润色不同，这是一次**整篇重写**：后端把「原笔记 + 新内容 + 提问」交给模型，
 * 产出把新知识放到合适位置的新正文（提示词是 skills/note-merge/SKILL.md）。
 * 同样只返回候选正文 —— 是否替换由用户在编辑器弹窗里点「替换正文」决定。
 *
 * @param {{noteId?:number,title?:string,note:string,question?:string,answer:string}} data
 * @returns {Promise<string>} 融入后的完整笔记 Markdown
 */
async function mergeNoteStream(data, onEvent, signal) {
  return streamPost('/api/ai/note-merge-stream', data, onEvent, signal)
}

/**
 * 「分节融入」两步（长笔记走这条，短笔记仍走上面的整篇重写）。
 *
 * 第一步只喂**大纲**（分节工具生成），拿回一个编号 —— 输出极小，所以定位这一步很便宜。
 * 第二步只喂**那一节**，拿回改写后的这一节正文。整篇的其它部分不经过模型，
 * 由 noteSections.applySectionPatch 按区间贴回去。
 *
 * @param {{title?:string,outline:string,question?:string,answer:string}} data
 * @returns {Promise<{action:'merge'|'append'|'covered',index:number,heading:string,reason:string}>}
 */
async function mergeLocate(data) {
  const res = await request.post('/ai/note-merge-locate', data, { timeout: AI_TIMEOUT })
  return res
}

/**
 * 第二步：改写指定小节（SSE）。返回整个 done 负载 `{content, heading, mode}` ——
 * content 是这一节的**正文**（不含标题行）。
 *
 * @param {{title?:string,outline?:string,heading:string,section:string,question?:string,answer:string,mode?:'rewrite'|'insert'}} data
 */
async function mergeSectionStream(data, onEvent, signal) {
  return streamPost('/api/ai/note-merge-section-stream', data, onEvent, signal, true)
}

/**
 * 流式 POST 的公共实现（润色 / 融入笔记 / 分节融入共用）。
 *
 * 为什么不用 axios：要逐段读响应流；而 axios 的响应拦截器做的是「一次性 json 解包 + 统一报错」，
 * 对 SSE 不适用。所以这里用 fetch 手工读流，并**自行复刻拦截器的报错文案**
 * （err.response.data.msg → err.message → '网络错误'），保证错误提示风格与其他接口一致。
 *
 * @param {boolean} [wantPayload] true = 返回 `done` 事件的**整个负载**（分节融入要拿 heading/mode），
 *                               默认只返回 content 字符串（润色/整体融入只要正文）
 */
async function streamPost(path, data, onEvent, signal, wantPayload = false) {
  let resp
  try {
    resp = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
      signal,
    })
  } catch (e) {
    // 主动取消：抛一个可识别的错误，调用方静默处理
    if (e?.name === 'AbortError') throw new Error('已取消处理')
    throw new Error('网络错误：' + (e?.message || '无法连接后端'))
  }

  if (!resp.ok) {
    let msg = `请求失败（HTTP ${resp.status}）`
    try {
      const j = await resp.json() // 后端异常时仍返回统一 Result 结构
      if (j?.msg) msg = j.msg
    } catch {
      /* 非 JSON 响应，保留默认文案 */
    }
    throw new Error(msg)
  }
  if (!resp.body) throw new Error('浏览器不支持流式响应，无法读取进度')

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  let content = ''
  let donePayload = null
  let failure = null

  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    // SSE 以「空行」分隔事件；最后一段可能不完整，留在 buf 里等下一块数据
    const blocks = buf.split('\n\n')
    buf = blocks.pop() ?? ''
    for (const block of blocks) {
      let event = 'message'
      let payloadText = ''
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) payloadText += line.slice(5).trim()
      }
      if (!payloadText) continue
      let payload
      try {
        payload = JSON.parse(payloadText)
      } catch {
        continue // 半截 JSON（极少见）直接跳过，不影响最终结果
      }
      if (event === 'done') {
        content = payload.content || ''
        donePayload = payload
      } else if (event === 'failed') failure = payload.message || 'AI 服务异常'
      else onEvent?.(event, payload)
    }
  }

  if (failure) throw new Error(failure)
  return wantPayload ? (donePayload || { content }) : content
}

export const aiApi = {
  /** 配置状态：{ configured, model } */
  status: () => request.get('/ai/status'),
  /** 语言润色 / 整理格式：{ text, mode: 'polish'|'format' } → 处理后的 Markdown（无进度） */
  polish: (data) => request.post('/ai/polish', data, { timeout: AI_TIMEOUT }),
  /** 同上，但带分段进度（SSE）。编辑器里的润色/整理格式走这个 */
  polishStream: (data, onEvent, signal) => polishStream(data, onEvent, signal),
  /** 把智能体的回答融入当前笔记（SSE，整篇重写；调用方预览后由用户决定是否替换正文） */
  mergeNoteStream: (data, onEvent, signal) => mergeNoteStream(data, onEvent, signal),
  /** 分节融入第一步：只喂大纲，拿回目标小节编号（长笔记用；输出极小） */
  mergeLocate: (data) => mergeLocate(data),
  /** 分节融入第二步：只改写那一节（SSE；返回 {content, heading, mode}） */
  mergeSectionStream: (data, onEvent, signal) => mergeSectionStream(data, onEvent, signal),
  /** 智能体对话：{ message, sessionId, history?, noteId?, noteTitle?, noteContext? } → AiChatVO（最多 8 轮工具调用，耗时叠加） */
  chat: (data) => request.post('/ai/chat', data, { timeout: AI_TIMEOUT }),
  /** 会话回看：把事件日志投影成气泡列表（刷新页面后靠它恢复对话） */
  session: (id) => request.get(`/ai/sessions/${id}`),
  /** 清空一个会话（含其事件） */
  deleteSession: (id) => request.delete(`/ai/sessions/${id}`),
  /** 确认执行一个待确认的写操作（在此之前数据库零改动） */
  approveAction: (id) => request.post(`/ai/actions/${id}/approve`),
  /** 取消一个待确认的写操作 */
  rejectAction: (id) => request.post(`/ai/actions/${id}/reject`),
  /** 技能清单：润色/整理格式的提示词来自 skills/<id>/SKILL.md，这里只做只读展示 */
  skills: () => request.get('/ai/skills'),
  /**
   * 上传 / 替换一个技能：把 content 写成 skills/<id>/SKILL.md
   * 元数据（frontmatter）默认沿用原值；上传内容自带 frontmatter 时才一起替换
   */
  saveSkill: (id, data) => request.put(`/ai/skills/${id}`, data),
  /** 连通性测试：用传入的（含未保存的）配置发一次最小请求 → 成功文案 */
  test: (data) => request.post('/ai/test', data || {}, { timeout: AI_TIMEOUT }),
}

export const settingsApi = {
  /** 当前生效值 + 内置默认值 */
  get: () => request.get('/settings'),
  /** 批量更新；value 空白 = 恢复默认；即时生效 */
  update: (data) => request.put('/settings', data),
}

/** 知识图谱：结构边现算，语义边由模型推断（rebuild 会花 token，需用户主动点） */
/**
 * 模型配置档案 + 智能体会话。
 *
 * 为什么单独一块：模型配置从"主模型 / 本地目标二选一"改成了**可无限新增的档案列表**，
 * 每个任务（对话/wiki/实体/影响/自检/图谱/抽取/重排/核对）各指一个档案；
 * 而"哪一次对话用哪个模型"由会话记住。这两件事是一体的。
 */
export const modelApi = {
  /** 档案清单（**不含明文密钥**，只有 hasKey 与 keyHint）+ 提供方预设 + 任务分工 */
  profiles: () => request.get('/model/profiles'),
  createProfile: (body) => request.post('/model/profiles', body),
  /** 更新；body.apiKey 传 '__KEEP__' 表示不改密钥（界面回传的是掩码） */
  updateProfile: (id, body) => request.put(`/model/profiles/${id}`, body),
  removeProfile: (id) => request.delete(`/model/profiles/${id}`),
  activateProfile: (id) => request.post(`/model/profiles/${id}/activate`, {}),
  /** 连通性探测：真实发一次最小请求，返回 ok / 耗时 / 失败原因与提示 */
  testProfile: (id) => request.post(`/model/profiles/${id}/test`, {}, { timeout: 60000 }),
  /**
   * 按「地址 + 密钥」自动获取模型列表（服务端 GET {baseUrl}/models）。
   * 用 POST：可能带着还没保存的密钥，不能进 URL。编辑已有档案时 apiKey 留空、传 profileId 即用库里那把。
   * 返回 { ok, models:[{id, ownedBy, embedding}], baseUrl, suggestedBaseUrl?, message, hint? }
   */
  discoverModels: (body) => request.post('/model/discover', body, { timeout: 60000 }),
  migrate: () => request.post('/model/profiles/migrate', {}),
  routing: () => request.get('/model/routing'),

  // ---- 会话（右边栏"新开/切换/删除"用）----
  sessions: (limit) => request.get('/model/sessions', { params: { limit } }),
  newSession: (body) => request.post('/model/sessions', body || {}),
  removeSession: (id) => request.delete(`/model/sessions/${id}`),
  renameSession: (id, title) => request.put(`/model/sessions/${id}/title`, { title }),
  /** 会话指定模型档案；profileId 传空 = 跟随任务分工表 */
  setSessionModel: (id, profileId) => request.put(`/model/sessions/${id}/model`, { profileId }),
}

/**
 * 代码库（独立于知识库）。
 *
 * 为什么单开一块：代码的信号密度天生低（样板/依赖/测试），混进知识检索会把个人笔记挤下去。
 * 三种检索模式对应三类问题：symbol=在哪定义（精确）、keyword=我写过什么（模糊）、all=默认合并。
 */
export const codeApi = {
  stats: () => request.get('/code/stats'),
  repos: () => request.get('/code/repos'),
  saveRepo: (id, data) => (id ? request.put(`/code/repos/${id}`, data) : request.post('/code/repos', data)),
  removeRepo: (id) => request.delete(`/code/repos/${id}`),
  snippets: (params) => request.get('/code/snippets', { params }),
  snippet: (id) => request.get(`/code/snippets/${id}`),
  saveSnippet: (id, data) => (id ? request.put(`/code/snippets/${id}`, data) : request.post('/code/snippets', data)),
  removeSnippet: (id) => request.delete(`/code/snippets/${id}`),
  /** mode: symbol | keyword | all */
  search: (q, params) => request.get('/code/search', { params: { q, ...params } }),
  /** 文件夹导入：files = [{path, text}]，后端负责识别语言与过滤噪声 */
  importFolder: (data) => request.post('/code/import-folder', data, { timeout: 120000 }),
}

export const kgApi = {
  graph: () => request.get('/kg/graph'),
  /** 版本指纹：前端按秒轮询，变了才重画图（"实时"的实现方式） */
  version: () => request.get('/kg/version'),
  rebuild: () => request.post('/kg/rebuild', {}, { timeout: AI_TIMEOUT }),

  // ---------------- 概念层（真正的知识图谱：实体 + 三元组） ----------------
  /** 概念图：实体 + 三元组 + 本体（关系词表与传递/对称属性） */
  concept: () => request.get('/kg/concept'),
  // GraphRAG 社区层：划分是纯本地计算（Leiden），不花 token
  communities: () => request.get('/kg/communities'),
  groupedCommunities: () => request.get('/kg/communities/grouped'),
  recomputeCommunities: () => request.post('/kg/communities/recompute', {}, { params: { ifStale: false } }),
  communityStatus: () => request.get('/kg/communities/status'),
  graphSearch: (q, mode = 'auto') => request.get('/kg/communities/search', { params: { q, mode, limit: 6, synthesize: false } }),
  /** 按用户选择生成最多三段摘要；普通浏览与检索不会调用模型 */
  summarizeCommunities: () => request.post('/kg/communities/summarize', {}, { params: { limit: 3 }, timeout: 390000 }),
  /** 跑构建流水线：素材 → 抽三元组 → 链接入库 → 规则推理 → 实体向量化 */
  buildConcept: () => request.post('/kg/concept/build', {}, { timeout: 60000 }),
  /** 构建进度（阶段/百分比/已抽三元组数） */
  conceptJob: (jobId) => request.get(`/kg/concept/jobs/${jobId}`),
  /** 邻居展开（多跳子图）：id 可以是概念名，会按别名解析 */
  neighbors: (id, hops) => request.get('/kg/concept/neighbors', { params: { id, hops } }),
  /** 两个概念之间的最短路径 */
  path: (from, to) => request.get('/kg/concept/path', { params: { from, to } }),
  /** 只跑规则推理，不调模型（免费） */
  reason: () => request.post('/kg/concept/reason', {}),
  /** 疑似重复实体（只提示，合并要人工确认） */
  duplicates: () => request.get('/kg/concept/duplicates'),
  merge: (from, to) => request.post('/kg/concept/merge', {}, { params: { from, to } }),
  /** 实体识别探针：给一句话，看认出哪些概念 */
  recognize: (q) => request.get('/kg/concept/recognize', { params: { q } }),
  removeConceptNode: (id) => request.delete(`/kg/concept/nodes/${id}`),
  removeConceptRelation: (id) => request.delete(`/kg/concept/relations/${id}`),
}

/** LLM wiki：按主题（分类/标签）生成，落库缓存，素材变了可自动增量重生成 */
export const wikiApi = {
  topics: () => request.get('/wiki/topics'),
  page: (topicKey) => request.get(`/wiki/pages/${topicKey}`),
  /** Actual generation chunks and their current hash state; this is not claim verification. */
  dependencies: (topicKey) => request.get('/wiki/dependencies', { params: { topicKey }, silentError: true }),
  /** 可选生成目标（主模型 / 本地或自建）+ 当前选择 */
  models: () => request.get('/wiki/models'),
  /** 发起生成：立刻返回任务（含 jobId），进度用 job() 轮询 */
  generate: (topicKey, target) =>
    request.post(`/wiki/pages/${topicKey}/generate`, {}, { params: { target }, timeout: 30000 }),
  /** 删除一页编译产物（原始素材一行不动；主题页/实体页/自检页下次生成会回来） */
  removePage: (topicKey) => request.delete(`/wiki/pages/${topicKey}`),
  /** 生成任务进度（阶段 / 百分比 / 已生成字数 / 质量结论） */
  job: (jobId) => request.get(`/wiki/jobs/${jobId}`),
  /** 编译实体/概念页 + 索引页（后台任务） */
  compileEntities: () => request.post('/wiki/entities/compile', {}, { timeout: 30000 }),
  entityJob: (jobId) => request.get(`/wiki/entities/jobs/${jobId}`),
  /** 影响分析探针：给定新素材，模型认为该更新哪些页 */
  impact: (text) => request.post('/wiki/impact', { text }, { timeout: 120000 }),
  /** ③ 局部重编译：影响分析 → 只重建受影响的页（进度复用 job()） */
  recompile: () => request.post('/wiki/recompile', {}, { timeout: 30000 }),
  /** ④ 语义自检：代码检查 + 模型检查 → 写入 lint 页 */
  lint: () => request.post('/wiki/lint', {}, { timeout: 300000 }),
  /** 模型分工表：每个任务走云端还是本地（含为什么） */
  modelRouting: () => request.get('/wiki/model-routing'),
  autoRefresh: () => request.get('/wiki/auto-refresh'),
  setAutoRefresh: (on) => request.put(`/wiki/auto-refresh?on=${on ? 'true' : 'false'}`),
}

export const fileApi = {
  list: (params) => request.get('/files', { params }),
  /**
   * 分页的资料列表（**界面用**）：`{ list, total, page, size }`。
   *
   * <p>与 `list` 并存而不是替换它：`list` 返回全量数组，智能体的 `list_files` 工具与
   * MCP 桥接按那个形状解析。界面用分页是因为 `el-table` 一次渲染全部行（没有虚拟滚动），
   * 上千份资料就是几千个单元格 DOM。
   */
  page: (params) => request.get('/files/page', { params }),
  upload: (file, categoryId) => {
    const fd = new FormData()
    fd.append('file', file)
    if (categoryId) fd.append('categoryId', categoryId)
    return request.post('/files/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  /** 抽取出来的正文（阅读器用；**不含在 detail 里**，因为正文动辄十几万字） */
  text: (id) => request.get(`/files/${id}/text`),
  /** 原文件阅读预览：Word 保留文档结构，Markdown 返回上传的原始源码。 */
  originalPreview: (id) => request.get(`/files/${id}/original-preview`, { timeout: 120000 }),
  /**
   * **排版还原**后的正文（阅读器「抽取正文」页用）。
   *
   * 与 text 的分工：text 是检索层实际用的那份纯文本（PDF 两栏会逐行交错，只能检索不能读），
   * 这个按键面坐标把两栏、段落、章节标题重建出来（title/authors/heading/para/bullet/meta）。
   * 单独一个接口：PDF 解析要几百毫秒到一两秒，而阅读器默认打开的是「原文」，切过来才付这个成本。
   */
  layout: (id) => request.get(`/files/${id}/text-layout`, { timeout: 120000 }),
  /** 仅在用户点击时，从公式原图识别 LaTeX；不上传碎字还原候选。 */
  recognizeFormula: (id, region) => request.post(`/files/${id}/formula-recognition`, region, { timeout: AI_TIMEOUT }),
  acceptFormulaRecognition: (id, candidate) => request.post(`/files/${id}/formula-recognition/accept`, candidate),
  formulaRecognitionCaps: () => request.get('/files/formula-recognition/capabilities'),
  /** 分段翻译（阅读器用）：只接受一段，超长会被后端拒绝并说明上限 */
  translate: (id, text, targetLang) => request.post(`/files/${id}/translate`, { text, targetLang }, { timeout: 180000 }),
  /** 翻译能力：用哪个档案翻、单段上限多少 */
  translateCaps: () => request.get('/files/translate/capabilities'),  /** 保存阅读位置（页码/缩放/模式）—— 阅读器防抖调用 */
  saveReading: (id, body) => request.put(`/files/${id}/reading-state`, body),
  /** 内联打开原文的地址（浏览器原生渲染 PDF/图片/文本，用于在线阅读） */
  rawUrl: (id) => `/api/files/${id}/raw`,
  /** 下载二进制：返回 axios response（blob） */
  download: (id) => request.get(`/files/${id}/download`, { responseType: 'blob' }),
  remove: (id) => request.delete(`/files/${id}`),
  /** 资料详情（分类名 + 抽取状态） */
  detail: (id) => request.get(`/files/${id}`),
  /** 更新手填说明 —— 抽不出正文的资料（图片/压缩包）靠它进检索 */
  updateSummary: (id, summary) => request.put(`/files/${id}/summary`, { summary }),
  /** 改文件名（**只改基名，扩展名不可改**：抽正文按扩展名选解析器，改了会解析错） */
  rename: (id, name) => request.put(`/files/${id}/name`, { name }),
  /** 换分类（资料按分类进知识图谱） */
  updateCategory: (id, categoryId) => request.put(`/files/${id}/category`, { categoryId }),
  /** 重新抽取正文 */
  reextract: (id) => request.post(`/files/${id}/reextract`),
}

/** 语义检索（向量索引）：状态 / 重建（带进度）/ 检索体检 */
export const kbApi = {
  status: () => request.get('/kb/status'),
  rebuild: () => request.post('/kb/rebuild', {}, { timeout: 30000 }),
  job: (jobId) => request.get(`/kb/jobs/${jobId}`),
  /** 同一问题对比「词面 vs 语义」的命中差异 —— 这是"资料进去了没有"的可验证方式 */
  probe: (q) => request.get('/kb/probe', { params: { q }, timeout: 60000 }),
  /**
   * 融合检索（词面 + 语义合并排序）：知识库「检索」页的默认检索方式。
   *
   * 为什么需要：词面 SQL LIKE 要求用词与资料完全一致 —— 搜「大量字符串拼接用哪个类性能更好」
   * 词面 0 命中，融合检索能找回《String / StringBuilder / StringBuffer 区别》。
   *
   * 返回数组，元素字段（后端实测）：`{ sourceType:'note'|'quick_ref'|'file', sourceId, title, category, text, score, seq }`
   * —— **没有 snippet 字段**，命中片段要用 text 自己截；也没有 updatedAt。
   * 注意：q 不能为空（后端空 q 直接 500），空查询要的是"最近知识"时走 knowledgeApi.search。
   */
  search: (q, topK = 10, { silentError = false } = {}) => request.get('/kb/search', { params: { q, topK }, timeout: 60000, silentError }),
}

/** 触发浏览器保存文件 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export default request
