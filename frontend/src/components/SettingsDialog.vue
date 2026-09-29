<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi, tagApi, settingsApi, aiApi, wikiApi, modelApi } from '../api'
import { extractPromptFromMd } from '../utils/promptFromMd'

const visible = defineModel({ type: Boolean, default: false })

const activeTab = ref('ai')
const saving = ref(false)
const loading = ref(false)
const testing = ref(false)
const configuredModel = ref('')
const presetLabel = ref('')

// ---------- AI / 外观设置 ----------
/** AI 相关字段（顺序即 diff / 重置顺序）。润色/格式提示词已移出：它们是技能文件，不再走设置接口 */
const AI_FIELDS = [
  'baseUrl', 'apiKey', 'model', 'maxTokens', 'temperature',
  'thinking', 'reasoningEffort', 'chatPrompt', 'webEnabled',
]
const emptyForm = () => ({
  baseUrl: '', apiKey: '', model: '', maxTokens: '', temperature: '',
  thinking: '', reasoningEffort: '', chatPrompt: '', webEnabled: '1',
})

/**
 * 设置分组。原来是一个「外观与 AI」标签页里塞了 8 个 section
 * （其中一个"旧配置"就 182 行），滚起来又长又找不到东西；
 * 现在拆成左侧分组菜单 + 右侧只显示当前分组，一屏就能看完一组。
 */
const pane = ref('model')
const PANES = [
  { id: 'model', name: '模型档案与分工' },
  { id: 'legacy', name: '模型参数' },
  { id: 'prompt', name: '对话提示词' },
  { id: 'skill', name: '技能' },
  { id: 'category', name: '分类' },
  { id: 'tag', name: '标签' },
]
/** 模型分工表：每个任务指向哪个**模型档案**（含"为什么是这个默认"） */
const routing = ref({ table: [], targets: [] })
const routingBusy = ref(false)

/** 模型档案列表（接口**不返回明文密钥**，只有 hasKey / keyHint） */
const profiles = ref([])
const presets = ref([])
const probingId = ref('')
const savingProfile = ref(false)
const editor = ref({ open: false, id: '', name: '', provider: 'custom', baseUrl: '', apiKey: '', model: '', note: '', hasKey: false, keyHint: '' })

async function loadRouting() {
  try {
    const d = await modelApi.profiles()
    profiles.value = d.profiles || []
    presets.value = d.presets || []
    // 分工表的目标就是档案列表，一起刷新，避免"档案改了、下拉还是旧的"
    routing.value = { table: d.routing || [], targets: (d.profiles || []).map((p) => ({ id: p.id, label: p.name, model: p.model })) }
    // 参数页默认落在当前激活档案上；该档案被删了就回退第一个
    if (!paramProfile.value || !profiles.value.some((pp) => pp.id === paramProfile.value)) {
      paramProfile.value = d.activeId || profiles.value[0]?.id || ''
    }
    loadParamOf(paramProfile.value)
  } catch (e) {
    routing.value = { table: [], targets: [] }
  }
}

const PROVIDER_LABEL = {
  deepseek: 'DeepSeek', kimi: 'Kimi', ark: '火山方舟', openai: 'OpenAI',
  ollama: '本地 Ollama', lmstudio: '本地 LM Studio', vllm: '本地 vLLM', custom: '自定义',
}

function providerLabel(p) {
  return PROVIDER_LABEL[p] || p || '自定义'
}

/**
 * 打开档案编辑器。
 * @param p    要编辑的档案（null = 新增）
 * @param pre  预设（新增时预填地址与模型名）
 */
function openProfileEditor(p, pre) {
  if (p) {
    editor.value = {
      open: true, id: p.id, name: p.name, provider: p.provider, baseUrl: p.baseUrl,
      apiKey: '', // **不回填密钥**：接口本来就只给掩码，回填会让用户以为可以改
      model: p.model, note: p.note || '', hasKey: p.hasKey, keyHint: p.keyHint,
    }
  } else {
    editor.value = {
      open: true, id: '', name: pre ? pre.name : '新档案', provider: pre ? pre.provider : 'custom',
      baseUrl: pre ? pre.baseUrl : '', apiKey: '', model: pre ? pre.model : '',
      note: pre ? pre.note : '', hasKey: false, keyHint: '',
    }
  }
}

async function saveProfile() {
  const e = editor.value
  if (!e.name.trim() || !e.baseUrl.trim() || !e.model.trim()) {
    ElMessage.warning('名称、Base URL、模型名都要填')
    return
  }
  savingProfile.value = true
  try {
    const body = {
      name: e.name.trim(), provider: e.provider, baseUrl: e.baseUrl.trim(),
      model: e.model.trim(), note: e.note ? e.note.trim() : '',
      // 编辑时留空 = 不改密钥；新增时留空 = 不设置
      apiKey: e.id ? (e.apiKey.trim() ? e.apiKey.trim() : '__KEEP__') : e.apiKey.trim(),
    }
    if (e.id) {
      await modelApi.updateProfile(e.id, body)
    } else {
      await modelApi.createProfile(body)
    }
    editor.value.open = false
    ElMessage.success('已保存')
    await loadRouting()
  } catch (err) {
    /* 拦截器已提示 */
  } finally {
    savingProfile.value = false
  }
}

/** 连通性探测：真实发一次请求，把失败原因与提示直接告诉用户 */
async function probeProfile(p) {
  probingId.value = p.id
  try {
    const r = await modelApi.testProfile(p.id)
    if (r.ok) {
      ElMessage.success(`${p.name}：${r.message}（${r.ms}ms）`)
    } else {
      ElMessage.error(`${p.name} 连不上：${r.message}${r.hint ? '　→ ' + r.hint : ''}`)
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    probingId.value = ''
  }
}

async function activateProfile(p) {
  try {
    await modelApi.activateProfile(p.id)
    ElMessage.success(`已把「${p.name}」设为对话默认`)
    await loadRouting()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function removeProfile(p) {
  try {
    await ElMessageBox.confirm(
      `删除档案「${p.name}」？<br><br><span style="color:#6b7280">· 指向它的任务会自动回退到默认档案，不会让任务卡死<br>· 密钥一并删除，且**不可恢复**</span>`,
      '删除模型档案',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', dangerouslyUseHTMLString: true }
    )
  } catch {
    return
  }
  try {
    await modelApi.removeProfile(p.id)
    ElMessage.success('已删除')
    await loadRouting()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function migrateProfiles() {
  try {
    const r = await modelApi.migrate()
    if (r.created > 0) {
      ElMessage.success(`已把原有配置迁移为 ${r.created} 个档案`)
    } else {
      ElMessage.info('没有可迁移的旧配置（或已经有档案了）')
    }
    await loadRouting()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/**
 * 「模型参数」页：选一个档案 + 改它这一套生成参数。
 *
 * 为什么不是全局一组参数、也不是重复填地址密钥：参数的最优值**跟着模型走** ——
 * 本地小模型要更小的输出上限，思考型模型下发温度会被忽略，机械任务要关掉思考才快。
 * 所以这里只做两件事：选档案、改参数；地址/密钥/模型名统一留给「模型档案与分工」。
 * 参数改完**立即写库**（不像其它页要按保存），因为它是这个档案的属性、不是弹窗的表单草稿。
 */
const paramProfile = ref('')
const param = ref({ maxTokens: null, temperature: null, thinking: '', reasoningEffort: '' })
const paramTesting = ref(false)

const paramProfileActive = computed(
  () => !!paramProfile.value && paramProfile.value === profiles.value.find((p) => p.active)?.id,
)
const paramProfileHint = computed(() => {
  const p = profiles.value.find((x) => x.id === paramProfile.value)
  if (!p) {
    return '还没有档案 —— 先去「模型档案与分工」加一个'
  }
  return `${providerLabel(p.provider)} · ${p.baseUrl} · ${p.model}` + (p.hasKey ? ` · 密钥 ${p.keyHint}` : ' · 未配置密钥')
})
/** 思考是否开着（决定强度可选、温度是否生效） */
const paramThinkingOn = computed(() => param.thinking === 'enabled')
const paramTempAllowed = computed(() => param.thinking !== 'enabled')

/** 把某个档案的参数读进编辑区 */
function loadParamOf(id) {
  const p = profiles.value.find((x) => x.id === id)
  param.value = {
    maxTokens: p?.maxTokens ?? null,
    temperature: p?.temperature ?? null,
    thinking: p?.thinking || '',
    reasoningEffort: p?.reasoningEffort || '',
  }
}

/** 保存单个参数到所选档案（空值 = 清除 = 跟随全局默认） */
async function saveParam(key, value) {
  if (!paramProfile.value) {
    ElMessage.warning('先选一个模型档案')
    return
  }
  try {
    await modelApi.updateProfile(paramProfile.value, { [key]: value === null || value === undefined ? '' : value })
    await loadRouting()
    loadParamOf(paramProfile.value)
    ElMessage.success('已保存到该档案')
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function activateParamProfile() {
  try {
    await modelApi.activateProfile(paramProfile.value)
    await loadRouting()
    ElMessage.success('已设为对话默认档案')
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 用该档案真实发一次最小请求，确认地址/密钥/模型名都对 */
async function testParamProfile() {
  if (!paramProfile.value) {
    return
  }
  paramTesting.value = true
  try {
    const r = await modelApi.testProfile(paramProfile.value)
    if (r.ok) {
      ElMessage.success(`${r.name}：${r.message}（${r.ms}ms）`)
    } else {
      ElMessage.error(`连不上：${r.message}${r.hint ? '　→ ' + r.hint : ''}`)
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    paramTesting.value = false
  }
}

/** 任务 → 设置字段名（与后端 SettingsController 的映射一一对应） */
function fieldOfTask(task) {
  return 'modelFor' + task.charAt(0).toUpperCase() + task.slice(1)
}

async function setTaskTarget(task, target) {
  routingBusy.value = true
  try {
    const payload = {}
    payload[fieldOfTask(task)] = target
    // 必须用 update：settingsApi 只有 get/update，写 save() 会抛 TypeError。
    // 当时那个空 catch 把它吞了 → 界面看起来"点了没反应"（实测踩到：任务分工改不动）。
    await settingsApi.update(payload)
    await loadRouting()
    ElMessage.success('已切换（下一次该任务生效）')
  } catch (e) {
    // 失败必须说出来：静默失败最难查（这次就是被静默吞掉才没人发现）
    ElMessage.error('切换失败：' + (e?.message || e))
  } finally {
    routingBusy.value = false
  }
}

const form = ref(emptyForm())
let baseline = emptyForm()
const overridden = ref({})
const defaults = ref({})

/**
 * 密钥的两个状态位。
 * 后端**不再回传明文 API Key**（只回 hasApiKey 布尔），所以输入框永远是空的：
 * - 输入框留空 且 未点清除 → 保存时不带该字段 → 已保存的密钥原样保留
 * - 输入框填了新值        → 保存时带上 → 替换密钥
 * - 点了「清除」          → 保存时发空串 → 后端删掉该行，回落到 .env
 */
const hasApiKey = ref(false)
const clearApiKey = ref(false)

// ---------- 提示词：从 .md 文件识别导入 ----------
/**
 * 对话提示词支持上传 .md 识别导入：
 * 把写好的提示词文档直接选进来，由 utils/promptFromMd
 * 剥掉说明性文字、只取要喂给模型的那一段。
 * 识别方式可随时切换（复用同一份文件内容，不必重新选文件），也能「撤销导入」还原导入前的值。
 * <p>
 * 润色 / 整合格式的提示词**不再走这条路**：它们已是 skills/&lt;id&gt;/SKILL.md 文件，
 * 由后端实时读取，界面上只做只读展示（见下方「技能」区块）。
 */
const fileInputRef = ref(null)
let importTarget = ''
/** field → { name, raw, mode, label, chars, prev } */
const promptImport = ref({ chatPrompt: null })

function pickPromptFile(field) {
  importTarget = field
  const el = fileInputRef.value
  if (!el) return
  el.value = '' // 清掉上次选择，否则连选同一个文件不触发 change
  el.click()
}

async function onPromptFile(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  const field = importTarget
  if (!file || !field) return
  let raw = ''
  try {
    raw = await file.text()
  } catch (err) {
    ElMessage.error('读取文件失败：' + (err?.message || '未知错误'))
    return
  }
  const picked = extractPromptFromMd(raw, 'auto')
  promptImport.value[field] = {
    name: file.name,
    raw,
    mode: 'auto',
    prev: form.value[field] || '', // 支持撤销导入
    ...picked,
  }
  form.value[field] = picked.text
  if (!picked.text || picked.text.trim().length < 30) {
    ElMessage.warning(`从 ${file.name} 里只识别出 ${picked.chars} 字，可能取错了部分 —— 可切换识别方式，或改用粘贴`)
  } else {
    ElMessage.success(`已从 ${file.name} 导入（${picked.label} · ${picked.chars} 字），保存后生效`)
  }
}

/** 切换识别方式：用同一份文件内容重新抽取 */
function rePromptMode(field, mode) {
  const cur = promptImport.value[field]
  if (!cur) return
  const picked = extractPromptFromMd(cur.raw, mode)
  promptImport.value[field] = { ...cur, mode, ...picked }
  form.value[field] = picked.text
}

/** 撤销导入：还原成导入前的内容 */
function undoPromptImport(field) {
  const cur = promptImport.value[field]
  if (!cur) return
  form.value[field] = cur.prev
  promptImport.value[field] = null
  ElMessage.info('已还原为导入前的内容')
}

/** 常见 OpenAI 兼容服务：一键填充地址 + 模型名（模型名以 2026-09 各家官方文档为准） */
const PRESETS = [
  { label: 'DeepSeek 官方', baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash' },
  { label: '阿里通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen3.8-flash' },
  { label: '月之暗面 Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'kimi-k3' },
  { label: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-5.3-flash' },
  { label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-5.6-luna' },
  { label: 'Google Gemini', baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai', model: 'gemini-3.8-flash' },
  { label: '硅基流动', baseUrl: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V4-Pro' },
  { label: '本地 Ollama', baseUrl: 'http://localhost:11434/v1', model: 'qwen3:8b' },
]

/**
 * 全量模型名（分组）。
 * 名称与能力说明以 2026-09 各厂商官方文档为准；DeepSeek 已把「思考」由独立模型
 * 改成请求参数，所以 v4 只有 flash / pro 两个主 ID。
 */
const MODEL_GROUPS = [
  {
    label: 'DeepSeek 官方（api.deepseek.com）',
    options: [
      { value: 'deepseek-flash', label: 'deepseek-flash · 快、便宜（官方主推，推荐）' },
      { value: 'deepseek-v4-pro', label: 'deepseek-v4-pro · 旗舰，复杂推理 / Agent' },
      { value: 'deepseek-reasoner', label: 'deepseek-reasoner · 旧名，仍可用；默认开思考' },
      { value: 'deepseek-chat', label: 'deepseek-chat · 旧名，仍可用；默认不开思考' },
      { value: 'deepseek-v4-flash', label: 'deepseek-v4-flash · 别名，等价 deepseek-flash' },
      { value: 'deepseek-v4-flash-vision-exp', label: 'deepseek-v4-flash-vision-exp · 别名，支持图片输入' },
    ],
  },
  {
    label: '阿里通义千问（DashScope）',
    options: [
      { value: 'qwen3.8-max', label: 'qwen3.8-max · 旗舰，推理+视觉' },
      { value: 'qwen3.8-flash', label: 'qwen3.8-flash · 均衡，推理+视觉' },
      { value: 'qwen3.7-max', label: 'qwen3.7-max · 上一代旗舰' },
      { value: 'qwen3.7-plus', label: 'qwen3.7-plus · 性价比' },
      { value: 'qwen3.6-flash', label: 'qwen3.6-flash · 轻量' },
      { value: 'qwen-long', label: 'qwen-long · 超长文档' },
    ],
  },
  {
    label: '月之暗面 Kimi',
    options: [
      { value: 'kimi-k3', label: 'kimi-k3 · 2.8T 旗舰，原生视觉，长周期任务' },
      { value: 'kimi-k2.7-code', label: 'kimi-k2.7-code · 代码特化' },
      { value: 'kimi-k2.6', label: 'kimi-k2.6 · 上一代主力' },
      { value: 'kimi-latest', label: 'kimi-latest · 跟随最新稳定版' },
    ],
  },
  {
    label: '智谱 GLM',
    options: [
      { value: 'glm-5.3', label: 'glm-5.3 · 旗舰，编程/安全专项' },
      { value: 'glm-5.3-flash', label: 'glm-5.3-flash · 轻量多模态，性价比高' },
      { value: 'glm-5.2', label: 'glm-5.2 · 上一代旗舰' },
      { value: 'glm-5', label: 'glm-5 · 稳定版' },
    ],
  },
  {
    label: 'OpenAI',
    options: [
      { value: 'gpt-5.6-sol', label: 'gpt-5.6-sol · 旗舰，最强推理' },
      { value: 'gpt-5.6-terra', label: 'gpt-5.6-terra · 主力，均衡' },
      { value: 'gpt-5.6-luna', label: 'gpt-5.6-luna · 轻量，高频便宜' },
      { value: 'gpt-5.5', label: 'gpt-5.5 · 上一代旗舰' },
    ],
  },
  {
    label: 'Anthropic Claude（需 OpenAI 兼容中转）',
    options: [
      { value: 'claude-sonnet-5', label: 'claude-sonnet-5 · 主力，性价比' },
      { value: 'claude-opus-5', label: 'claude-opus-5 · 旗舰，编码强' },
      { value: 'claude-opus-4-8', label: 'claude-opus-4-8 · 上一代旗舰' },
      { value: 'claude-haiku-4-5', label: 'claude-haiku-4-5 · 轻量低延迟' },
    ],
  },
  {
    label: 'Google Gemini',
    options: [
      { value: 'gemini-3.8-flash', label: 'gemini-3.8-flash · 高吞吐，Agent' },
      { value: 'gemini-3.6-flash', label: 'gemini-3.6-flash · 主力' },
      { value: 'gemini-3.5-flash', label: 'gemini-3.5-flash · 可调思考等级' },
      { value: 'gemini-3.5-flash-lite', label: 'gemini-3.5-flash-lite · 超轻量' },
    ],
  },
  {
    label: 'xAI Grok',
    options: [
      { value: 'grok-4.6', label: 'grok-4.6 · 中端，编码性价比高' },
      { value: 'grok-4.5', label: 'grok-4.5 · 上一代' },
    ],
  },
  {
    label: '其他',
    options: [
      { value: 'MiniMax-M2.5', label: 'MiniMax-M2.5 · MiniMax 旗舰' },
      { value: 'hy4-preview', label: 'hy4-preview · 腾讯混元 Hy4（770B）' },
      { value: 'hy3', label: 'hy3 · 腾讯混元 Hy3' },
    ],
  },
  {
    label: '已失效（调用会返回 400，仅用于识别错误配置）',
    options: [
      { value: 'deepseek-v4-pro-0813', label: 'deepseek-v4-pro-0813 · 带日期的快照名，官方已不收', disabled: true },
      { value: 'deepseek-v4-flash-0731', label: 'deepseek-v4-flash-0731 · 带日期的快照名，官方已不收', disabled: true },
      { value: 'deepseek-v3.2', label: 'deepseek-v3.2 · 已不在官方支持列表', disabled: true },
      { value: 'deepseek-v3', label: 'deepseek-v3 · 已不在官方支持列表', disabled: true },
      { value: 'gpt-4o-mini', label: 'gpt-4o-mini · 旧版 GPT-4 系列', disabled: true },
    ],
  },
]

/**
 * 旧名 → 建议替换成的新名。
 * 这些旧名目前**仍然可用**（DeepSeek 侧仍做路由），只是不再是官方文档里的主名，
 * 所以这里只做提示 + 一键替换，绝不静默改写用户已保存的配置——
 * 因为 deepseek-chat 与 deepseek-flash 的「思考默认开/关」并不相同，静默替换会改变行为。
 */
const LEGACY_MODEL_HINTS = {
  'deepseek-chat': 'deepseek-flash',
  'deepseek-reasoner': 'deepseek-flash',
  'deepseek-v4-flash': 'deepseek-flash',
}

/** 实测会直接 400 的模型名（DeepSeek 官方端点明确返回「不支持」） */
const DEAD_MODELS = [
  'deepseek-v4', 'deepseek-flash-0731', 'deepseek-v4-pro-0813',
  'deepseek-v4-flash-0731', 'deepseek-v3.2', 'deepseek-v3',
]

/**
 * 天生带思考的模型前缀（思考默认开启）。
 * 与后端 DeepSeekClient.THINKING_ON_BY_DEFAULT 保持一致。
 * 注意 deepseek-chat 不在其中——它是历史遗留的「非思考」通道。
 */
const THINKING_ON_BY_DEFAULT = [
  'deepseek-reasoner', 'deepseek-flash', 'deepseek-v4',
  'kimi-k3', 'kimi-k2.7', 'kimi-k2.6', 'kimi-latest',
  'glm-5', 'qwen3.8', 'qwen3.7', 'qwen3.6', 'qwq',
  'gpt-5', 'gpt-6', 'o1', 'o3', 'o4',
  'claude-', 'gemini-3', 'gemini-2.5', 'grok-4',
]

/** 完全不能带 temperature 的模型（思考无法关闭，官方要求不下发采样参数） */
const TEMPERATURE_FORBIDDEN = [
  'kimi-k3', 'kimi-k2.7', 'kimi-k2.6', 'kimi-latest',
  'glm-5', 'qwen3.8', 'qwen3.7', 'qwen3.6', 'qwq',
  'gpt-5', 'gpt-6', 'o1', 'o3', 'o4',
  'claude-', 'gemini-3', 'gemini-2.5', 'grok-4',
]

const startsWithAny = (m, list) =>
  list.some((p) => (m || '').trim().toLowerCase().startsWith(p))

const isThinkingModel = (m) => startsWithAny(m, THINKING_ON_BY_DEFAULT)

/** 旧名提示（有值才提示） */
const legacyReplacement = computed(
  () => LEGACY_MODEL_HINTS[(form.value.model || '').trim().toLowerCase()] || '',
)

/** 该模型名是否已被官方拒收 */
const modelIsDead = computed(
  () => DEAD_MODELS.includes((form.value.model || '').trim().toLowerCase()),
)

/** 本次请求思考模式是否开启（自动 = 由模型决定） */
const thinkingOn = computed(() => {
  const t = (form.value.thinking || '').trim()
  if (t === 'enabled') return true
  if (t === 'disabled') return false
  return isThinkingModel(form.value.model)
})

/** 温度是否真的会被服务端采纳（与后端判定一致） */
const tempAllowed = computed(
  () => !thinkingOn.value && !startsWithAny(form.value.model, TEMPERATURE_FORBIDDEN),
)

/** 温度滑块：以数字双向绑定 form.temperature（字符串，便于与后端统一 diff） */
const tempNum = computed({
  get: () => {
    const n = Number(form.value.temperature)
    return Number.isFinite(n) ? n : 0.4
  },
  set: (v) => {
    form.value.temperature = String(v)
  },
})

function applyPreset(label) {
  const p = PRESETS.find((x) => x.label === label)
  if (!p) return
  form.value.baseUrl = p.baseUrl
  form.value.model = p.model
  ElMessage.success(`已填入「${p.label}」的地址与模型，记得填 API Key 再保存`)
}

/** 一键把旧模型名换成现行主名（旧名仍可用，只是不再推荐） */
function fixLegacyModel() {
  if (!legacyReplacement.value) return
  if (modelIsDead.value) return
  const old = form.value.model
  form.value.model = legacyReplacement.value
  ElMessage.success(`已把 ${old} 换成 ${legacyReplacement.value}`)
}

/** 把后端返回的快照灌进表单（同时刷新 baseline / 覆盖标记 / 默认值） */
function applySnapshot(s) {
  const next = emptyForm()
  for (const k of AI_FIELDS) next[k] = s[k] == null ? '' : String(s[k])
  // 后端返回的是布尔，面板里用 '1'/'0' 与其它字段保持同一种形状（都是字符串）
  next.webEnabled = s.webEnabled ? '1' : '0'
  form.value = next
  baseline = { ...next }
  overridden.value = {
    baseUrl: !!s.baseUrlOverridden,
    apiKey: !!s.apiKeyOverridden,
    model: !!s.modelOverridden,
    maxTokens: !!s.maxTokensOverridden,
    temperature: !!s.temperatureOverridden,
    thinking: !!s.thinkingOverridden,
    reasoningEffort: !!s.reasoningEffortOverridden,
    chatPrompt: !!s.chatOverridden,
  }
  if (s.defaults) defaults.value = s.defaults
  hasApiKey.value = !!s.hasApiKey
  clearApiKey.value = false
}

// ---------- 分类 / 标签管理 ----------
const categories = ref([])
const tags = ref([])
const catLoading = ref(false)
const tagLoading = ref(false)

// ---------- 技能（润色 / 整理格式的提示词） ----------
const skills = ref([])
const skillsLoading = ref(false)

/** 技能服务于哪个按钮（后端固定映射：润色 → markdown-polish，整理格式 → markdown-beautify） */
const SKILL_USAGE = {
  polish: 'AI 润色',
  format: '整理格式',
}
function skillUsage(appliesTo) {
  return SKILL_USAGE[appliesTo] || appliesTo || '未指定'
}

async function loadSkills() {
  skillsLoading.value = true
  try {
    const r = await aiApi.skills()
    skills.value = r?.skills || []
  } catch (e) {
    skills.value = []
  } finally {
    skillsLoading.value = false
  }
}

/**
 * 上传技能：选一个 .md/.txt 覆盖 skills/<id>/SKILL.md。
 * - 「替换」按钮 → 带上该技能 id；「上传技能」按钮 → id 为空，需填新技能名（即新建）
 * - 识别方式默认「整体导入」：技能文件本身就含 frontmatter，不能像导入提示词那样默认去抽取，
 *   否则会把 frontmatter 一起剥掉、元数据（applies_to / min_ratio）就丢了
 */
const skillFileRef = ref(null)
const skillImport = ref(null)
const skillSaving = ref(false)

function pickSkillFile(skillId) {
  skillImport.value = { id: skillId || '', name: '', raw: '', mode: 'whole', chars: 0, newId: '' }
  const el = skillFileRef.value
  if (!el) return
  el.value = '' // 清掉上次选择，否则连选同一个文件不触发 change
  el.click()
}

async function onSkillFile(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) {
    skillImport.value = null
    return
  }
  let raw = ''
  try {
    raw = await file.text()
  } catch (err) {
    ElMessage.error('读取文件失败：' + (err?.message || '未知错误'))
    return
  }
  const cur = skillImport.value || { id: '', newId: '' }
  skillImport.value = { ...cur, name: file.name, raw, mode: 'whole', chars: raw.length }
}

/** 按当前识别方式算出真正要写入的正文（供预览字数） */
function skillText(it) {
  if (!it) return ''
  if (it.mode === 'whole') return it.raw
  return extractPromptFromMd(it.raw, it.mode).text
}

function reSkillMode(mode) {
  const it = skillImport.value
  if (!it) return
  it.mode = mode
  it.chars = skillText({ ...it, mode }).length
}

async function confirmSkillUpload() {
  const it = skillImport.value
  if (!it) return
  const id = (it.id || it.newId || '').trim().toLowerCase()
  if (!id) {
    ElMessage.warning('请先填技能名')
    return
  }
  if (!/^[a-z0-9][a-z0-9._-]*$/.test(id)) {
    ElMessage.warning('技能名只能用小写字母、数字、点、下划线、连字符')
    return
  }
  const content = skillText(it)
  if (!content.trim()) {
    ElMessage.warning('文件内容为空')
    return
  }
  skillSaving.value = true
  try {
    await aiApi.saveSkill(id, { content })
    ElMessage.success(`已写入 skills/${id}/SKILL.md，立即生效`)
    skillImport.value = null
    await loadSkills()
  } catch (e) {
    // 失败原因（技能名非法 / 内容为空等）由请求拦截器统一弹出
  } finally {
    skillSaving.value = false
  }
}

// immediate 必须开：本组件是被 AppLayout 懒加载的（defineAsyncComponent + v-if），
// 「首次点设置」时挂载与 visible=true 发生在同一拍 —— 组件挂载好的瞬间 visible 就已经是
// true，不存在 false→true 的变化。没有 immediate 的话这条 watch 永远不触发，
// load / loadCategories / loadTags 一次都不会跑，设置表单和分类/标签表格全是空的。
watch(visible, (v) => {
  if (v) {
    activeTab.value = 'ai'
    presetLabel.value = ''
    load()
    loadCategories()
    loadTags()
    loadSkills()
  loadRouting()
  }
}, { immediate: true })

async function load() {
  loading.value = true
  // 每次打开设置都清掉上一次的导入提示（重新加载后内容已经变了）
  promptImport.value = { chatPrompt: null }
  skillImport.value = null
  try {
    const s = await settingsApi.get()
    applySnapshot(s)
    const st = await aiApi.status().catch(() => null)
    configuredModel.value = st?.model || ''
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const payload = {}
    for (const k of AI_FIELDS) {
      if (k === 'apiKey') continue // 密钥单独处理，见下
      if (form.value[k] !== baseline[k]) payload[k] = form.value[k]
    }
    // 密钥三态：填了新值=替换；点了清除=发空串（后端删行）；都没动=不带该字段（保留原密钥）
    if (form.value.apiKey) {
      payload.apiKey = form.value.apiKey
    } else if (clearApiKey.value) {
      payload.apiKey = ''
    }
    if (Object.keys(payload).length === 0) {
      ElMessage.info('没有需要保存的修改')
      return
    }
    applySnapshot(await settingsApi.update(payload))
    const st = await aiApi.status().catch(() => null)
    configuredModel.value = st?.model || ''
    ElMessage.success('设置已保存，即时生效')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

/** 清除已保存的密钥：只打标记，真正删除发生在点「保存」时（发空串） */
function clearSavedKey() {
  clearApiKey.value = true
  form.value.apiKey = ''
  ElMessage.info('保存后将清除已保存的密钥，回落到后端 .env 里的密钥')
}

/** 用当前表单值（含未保存的改动）发一次极短请求，验证地址/密钥/模型是否可用 */
async function testConn() {
  testing.value = true
  try {
    const msg = await aiApi.test({
      baseUrl: form.value.baseUrl,
      apiKey: form.value.apiKey,
      model: form.value.model,
      maxTokens: form.value.maxTokens,
      temperature: form.value.temperature,
      thinking: form.value.thinking,
      reasoningEffort: form.value.reasoningEffort,
    })
    ElMessage.success({ message: msg, duration: 6000, showClose: true })
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    testing.value = false
  }
}

async function resetAll() {
  await ElMessageBox.confirm(
    '把所有 AI 设置恢复成内置默认？'
    + (hasApiKey.value ? '已保存的 API Key 也会被清除（之后使用后端 .env 里的密钥）。' : ''),
    '全部恢复默认',
    { type: 'warning', confirmButtonText: '恢复默认', confirmButtonClass: 'el-button--danger' },
  )
  saving.value = true
  try {
    const blank = {}
    for (const k of AI_FIELDS) blank[k] = ''
    applySnapshot(await settingsApi.update(blank))
    overridden.value = {}
    presetLabel.value = ''
    ElMessage.success('已全部恢复默认')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

/** 单项恢复默认：留空保存后后端即回落默认值 */
function resetField(key) {
  form.value[key] = defaults.value[key] || ''
  // 恢复默认时把「已从 xxx.md 导入」的提示一并清掉，避免状态与实际内容不符
  if (promptImport.value[key]) promptImport.value[key] = null
}

// ---------- 分类管理 ----------
async function loadCategories() {
  catLoading.value = true
  try {
    categories.value = await categoryApi.tree()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    catLoading.value = false
  }
}

/** 新增根分类或子分类 */
async function addCategory(parentId = 0) {
  const parent = parentId ? findCat(categories.value, parentId) : null
  const { value } = await ElMessageBox.prompt(
    parent ? `新建「${parent.name}」的子分类：` : '新建顶级分类：',
    '新增分类',
    { inputPlaceholder: '分类名称', inputValidator: (v) => !!v?.trim() || '名称不能为空' },
  )
  await categoryApi.add({ name: value.trim(), parentId })
  ElMessage.success('分类已创建')
  loadCategories()
  notifyMetaChanged()
}

/** 重命名分类 */
async function renameCategory(row) {
  const { value } = await ElMessageBox.prompt('新的分类名称：', '重命名分类', {
    inputValue: row.name,
    inputValidator: (v) => !!v?.trim() || '名称不能为空',
  })
  await categoryApi.update(row.id, { name: value.trim() })
  ElMessage.success('已重命名')
  loadCategories()
  notifyMetaChanged()
}

/** 删除分类（有子分类时后端拒绝；其下内容自动变为未分类） */
async function removeCategory(row) {
  await ElMessageBox.confirm(
    `删除分类「${row.name}」？其下笔记/速查卡/资料将变为「未分类」。`,
    '删除分类',
    { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' },
  )
  const r = await categoryApi.remove(row.id)
  ElMessage.success(`已删除分类${r?.affectedNotes ? `，${r.affectedNotes} 条内容已移到未分类` : ''}`)
  loadCategories()
  notifyMetaChanged()
}

function findCat(nodes, id) {
  for (const n of nodes || []) {
    if (n.id === id) return n
    const hit = findCat(n.children, id)
    if (hit) return hit
  }
  return null
}

// ---------- 标签管理 ----------
async function loadTags() {
  tagLoading.value = true
  try {
    tags.value = await tagApi.list()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    tagLoading.value = false
  }
}

async function addTag() {
  const { value } = await ElMessageBox.prompt('新标签名称：', '新增标签', {
    inputPlaceholder: '标签名',
    inputValidator: (v) => !!v?.trim() || '名称不能为空',
  })
  await tagApi.add(value.trim())
  ElMessage.success('标签已创建')
  loadTags()
  notifyMetaChanged()
}

async function renameTag(row) {
  const { value } = await ElMessageBox.prompt('新的标签名称：', '重命名标签', {
    inputValue: row.name,
    inputValidator: (v) => !!v?.trim() || '名称不能为空',
  })
  await tagApi.rename(row.id, value.trim())
  ElMessage.success('已重命名')
  loadTags()
  notifyMetaChanged()
}

/** 删除标签（会从所有笔记上移除） */
async function removeTag(row) {
  await ElMessageBox.confirm(
    row.useCount > 0
      ? `删除标签「${row.name}」？它正被 ${row.useCount} 篇笔记使用，删除后将从这些笔记上移除。`
      : `删除标签「${row.name}」？`,
    '删除标签',
    { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' },
  )
  const r = await tagApi.remove(row.id)
  ElMessage.success(`已删除标签${r?.affectedNotes ? `，已从 ${r.affectedNotes} 篇笔记移除` : ''}`)
  loadTags()
  notifyMetaChanged()
}

/** 通知其它页面：分类/标签已变化（用于刷新下拉框等） */
function notifyMetaChanged() {
  window.dispatchEvent(new CustomEvent('lh-meta-changed'))
}
</script>
<template>
  <el-dialog v-model="visible" title="⚙ 设置" width="880px" top="8vh" destroy-on-close>
    <div class="settings-body">
      <nav class="settings-nav">
        <button
          v-for="g in PANES"
          :key="g.id"
          type="button"
          class="nav-item"
          :class="{ on: pane === g.id }"
          @click="pane = g.id"
        >{{ g.name }}</button>
      </nav>

      <div class="settings-pane">
        <div v-show="pane === 'legacy'">
          <section class="sec">
            <h4 class="sec-title">选择模型档案</h4>
            <p class="hint net-hint">
              这一节只管<b>生成参数</b>——模型本身的地址、密钥、模型名在「模型档案与分工」里配，
              不再两处都能改。选一个档案，下面改的就是<b>它</b>的参数：不同模型想要的并不一样
              （本地小模型要更小的输出上限、思考型模型下发温度会被忽略），所以参数跟着档案走。
            </p>
            <div class="field">
              <label class="lbl">模型档案</label>
              <div class="inline">
                <el-select v-model="paramProfile" style="width: 280px" @change="loadParamOf">
                  <el-option v-for="p in profiles" :key="p.id" :label="p.name + '（' + p.model + '）'" :value="p.id" />
                </el-select>
                <el-button size="small" :loading="paramTesting" @click="testParamProfile">测试连接</el-button>
                <el-tag v-if="paramProfileActive" size="small" type="success" effect="plain">当前生效</el-tag>
                <el-button v-else size="small" :disabled="!paramProfile" @click="activateParamProfile">设为生效</el-button>
              </div>
              <p class="hint">{{ paramProfileHint }}</p>
            </div>
          </section>

          <section class="sec">
            <h4 class="sec-title">生成参数</h4>
            <p class="hint net-hint">
              留空 = <b>跟随全局默认</b>；改完立即保存到该档案，不需要点下面的「保存」。
            </p>
            <div class="field">
              <label class="lbl">思考模式</label>
              <div class="inline">
                <el-radio-group :model-value="param.thinking" size="small" @change="(v) => saveParam('thinking', v)">
                  <el-radio-button value="">跟随默认</el-radio-button>
                  <el-radio-button value="enabled">开启</el-radio-button>
                  <el-radio-button value="disabled">关闭</el-radio-button>
                </el-radio-group>
                <el-select
                  :model-value="param.reasoningEffort"
                  size="small"
                  class="effort-select"
                  :disabled="!paramThinkingOn"
                  placeholder="思考强度"
                  @change="(v) => saveParam('reasoningEffort', v)"
                >
                  <el-option label="强度：服务端默认" value="" />
                  <el-option label="none · 几乎不思考，最省" value="none" />
                  <el-option label="minimal · 极简思考" value="minimal" />
                  <el-option label="low · 简单任务" value="low" />
                  <el-option label="medium · 中等" value="medium" />
                  <el-option label="high · 日常（推荐）" value="high" />
                  <el-option label="xhigh · 较高" value="xhigh" />
                  <el-option label="max · 复杂推理，最贵" value="max" />
                </el-select>
              </div>
              <p class="hint">
                「跟随默认」= 由模型决定：DeepSeek 的 flash / reasoner 与 V4 系列默认开启思考，deepseek-chat 默认不开。
                思考强度仅对 DeepSeek 官方端点（api.deepseek.com）生效。
                <b>润色 / 整理格式默认走快速通道（不思考）</b>，只有把思考模式显式设为「开启」才会让它们也思考。
              </p>
            </div>
            <div class="field-row">
              <div class="field grow">
                <label class="lbl">最大输出</label>
                <div class="inline">
                  <el-input
                    :model-value="param.maxTokens"
                    placeholder="跟随默认（8192）"
                    style="width: 170px"
                    @change="(v) => saveParam('maxTokens', v)"
                  />
                  <span class="unit">tokens</span>
                </div>
              </div>
              <div class="field grow">
                <label class="lbl">
                  温度
                  <el-tag v-if="!paramTempAllowed" size="small" type="info" effect="plain">当前不生效</el-tag>
                </label>
                <div class="inline">
                  <el-slider
                    :model-value="param.temperature === null ? 0.3 : Number(param.temperature)"
                    :min="0"
                    :max="2"
                    :step="0.1"
                    :disabled="!paramTempAllowed"
                    class="temp-slider"
                    @change="(v) => saveParam('temperature', v)"
                  />
                  <span class="unit">{{ param.temperature === null ? '跟随默认' : Number(param.temperature).toFixed(1) }}</span>
                </div>
                <p v-if="!paramTempAllowed" class="hint">
                  思考模式已开启：DeepSeek V4 等模型在思考时会忽略温度，请求里已不带该参数。
                </p>
              </div>
            </div>
          </section>
        </div>

        <div v-show="pane === 'model'">
          <section class="sec">
            <h4 class="sec-title">模型配置档案</h4>
            <p class="hint net-hint">
              每个档案是一套「服务商 + 地址 + 密钥 + 模型」组合，<b>可以加很多个</b>：
              云端强模型做判断类任务、本地模型做批量摘要，互不影响。
              点「启用」即把该档案设为对话默认；各任务具体用哪个，在下面的「模型分工」里逐项选。
            </p>

            <div v-for="p in profiles" :key="p.id" class="profile-row" :class="{ 'profile-active': p.active }">
              <div class="profile-main">
                <b class="profile-name">{{ p.name }}</b>
                <span v-if="p.active" class="badge badge-ok">当前生效</span>
                <span class="hint profile-meta">
                  {{ providerLabel(p.provider) }} · {{ p.model }}
                  <template v-if="p.hasKey"> · 密钥 {{ p.keyHint }}</template>
                  <template v-else> · 未配置密钥</template>
                </span>
              </div>
              <div class="profile-acts">
                <el-button size="small" @click="openProfileEditor(p)">编辑</el-button>
                <el-button size="small" :loading="probingId === p.id" @click="probeProfile(p)">测试</el-button>
                <el-button size="small" :disabled="p.active" @click="activateProfile(p)">启用</el-button>
                <el-button size="small" :disabled="profiles.length <= 1" @click="removeProfile(p)">删除</el-button>
              </div>
            </div>
            <p v-if="!profiles.length" class="hint">
              还没有档案 —— 从下面的预设加一个（也可以用 <b>迁移旧配置</b> 把原来填过的地址/密钥搬过来）
            </p>

            <div class="preset-chips">
              <button v-for="pre in presets" :key="pre.provider + pre.name" type="button" class="preset-chip"
                      @click="openProfileEditor(null, pre)">
                ＋{{ pre.name }}
              </button>
              <button type="button" class="preset-chip preset-migrate" @click="migrateProfiles">
                迁移旧配置
              </button>
            </div>
          </section>

          <!-- 档案编辑器：新增/编辑共用 -->
          <section v-if="editor.open" class="sec">
            <h4 class="sec-title">{{ editor.id ? '编辑档案' : '新增档案' }}</h4>
            <div class="form-grid">
              <label class="fl">名称</label>
              <el-input v-model="editor.name" placeholder="如：DeepSeek 云端 / 本地 Ollama" />
              <label class="fl">服务商</label>
              <el-select v-model="editor.provider" style="width: 100%">
                <el-option v-for="pre in presets" :key="pre.provider" :label="pre.name" :value="pre.provider" />
              </el-select>
              <label class="fl">Base URL</label>
              <el-input v-model="editor.baseUrl" placeholder="OpenAI 兼容基址，本地 Ollama 是 http://localhost:11434/v1" />
              <label class="fl">API Key</label>
              <el-input v-model="editor.apiKey" :placeholder="editor.hasKey ? '已配置（' + editor.keyHint + '），留空保持不变' : '本地服务随便填一个非空值即可'" />
              <label class="fl">模型名</label>
              <el-input v-model="editor.model" placeholder="如 deepseek-flash / qwen3:8b" />
              <label class="fl">备注</label>
              <el-input v-model="editor.note" placeholder="这档准备用来干什么（可空）" />
            </div>
            <div class="ai-actions">
              <el-button size="small" type="primary" :loading="savingProfile" @click="saveProfile">保存</el-button>
              <el-button size="small" @click="editor.open = false">取消</el-button>
              <span class="hint">保存后可点列表里的「测试」真实发一次请求，确认地址与模型名都对</span>
            </div>
          </section>

          <section class="sec">
            <h4 class="sec-title">模型分工（每个后台任务用哪个档案）</h4>
            <p class="hint net-hint">
              每个**后台任务**可以单独指定一个档案。<b>默认值是按实测定的</b>：判断类任务（实体页编译、影响分析、
              语义自检、图谱关联、三元组抽取、答案核对）用当前生效档案——小模型实测守不住跨页规则、标签也不稳定；
              批量摘要类任务（主题 wiki、检索词扩展）优先本地档案——免费且够用。
              <b>检索重排是例外</b>：它要读懂"问题与候选的关系"，97 条用例实测本地 8B 几乎无增益
              （MRR 0.759/0.743，≈不重排），换云端档案是 0.902——所以默认指向当前生效档案。
              嵌入向量固定用本地（换模型必须重建索引，所以不走这里）。
              <b>对话的模型不在这里选</b>：在智能体界面按会话选 —— 每个会话可固定一个档案，或选「默认」用当前生效档案。
            </p>
            <div v-for="row in routing.table" :key="row.task" class="route-row">
              <div class="route-main">
                <b class="route-label">{{ row.label }}</b>
                <span class="hint">{{ row.why }}</span>
              </div>
              <el-select
                :model-value="row.target"
                size="small"
                :disabled="routingBusy"
                @change="(v) => setTaskTarget(row.task, v)"
              >
                <el-option
                  v-for="t in routing.targets"
                  :key="t.id"
                  :label="t.label + '（' + t.model + '）'"
                  :value="t.id"
                />
              </el-select>
            </div>
          </section>

        </div>

        <div v-show="pane === 'prompt'">
          <section class="sec">
            <h4 class="sec-title">
              对话提示词
              <el-tag v-if="overridden.chatPrompt" size="small" type="warning" effect="plain">已自定义</el-tag>
              <el-link v-if="overridden.chatPrompt" type="primary" :underline="false" class="reset-link" @click="resetField('chatPrompt')">恢复默认</el-link>
            </h4>
            <el-input
              v-model="form.chatPrompt"
              type="textarea"
              :rows="6"
              placeholder="留空 = 使用内置默认。这是悬浮智能体的系统提示词：讲解方式、用户背景、工具使用纪律都写在这里"
            />
            <div class="import-bar">
              <el-button size="small" @click="pickPromptFile('chatPrompt')">📄 导入 .md</el-button>
              <template v-if="promptImport.chatPrompt">
                <span class="import-file" :title="promptImport.chatPrompt.name">{{ promptImport.chatPrompt.name }}</span>
                <span class="hint">{{ promptImport.chatPrompt.label }} · {{ promptImport.chatPrompt.chars }} 字</span>
                <el-select
                  :model-value="promptImport.chatPrompt.mode"
                  size="small"
                  class="import-mode"
                  @change="(m) => rePromptMode('chatPrompt', m)"
                >
                  <el-option label="自动识别" value="auto" />
                  <el-option label="仅取代码块" value="fence" />
                  <el-option label="整体导入" value="whole" />
                </el-select>
                <el-link type="primary" :underline="false" class="reset-link" @click="undoPromptImport('chatPrompt')">撤销导入</el-link>
              </template>
              <span v-else class="hint">改完即时生效，不用重启后端；对话里说的「口径」都在这里定义</span>
            </div>
          </section>
        </div>

        <div v-show="pane === 'skill'">
          <section class="sec">
            <h4 class="sec-title">
              技能
              <span class="sec-actions">
                <el-button size="small" @click="pickSkillFile('')">上传技能</el-button>
                <el-link type="primary" :underline="false" class="skill-reload" :disabled="skillsLoading" @click="loadSkills">
                  重新读取
                </el-link>
              </span>
            </h4>
            <div v-if="skillsLoading" class="hint">读取中…</div>
            <div v-else-if="!skills.length" class="skill-empty">
              <p class="hint">没找到技能文件，可点「上传技能」新建（目录：<code>skills/&lt;技能名&gt;/SKILL.md</code>）</p>
            </div>
            <div v-else class="skill-list">
              <div v-for="s in skills" :key="s.id" class="skill-item">
                <div class="skill-row">
                  <span class="skill-name">{{ s.name }}</span>
                  <el-tag size="small" effect="plain">{{ skillUsage(s.appliesTo) }}</el-tag>
                  <span class="hint skill-meta">{{ s.chars }} 字</span>
                  <el-button size="small" text type="primary" @click="pickSkillFile(s.id)">替换</el-button>
                </div>
                <div class="skill-path" :title="s.description ? s.description + '\n' + s.path : s.path">{{ s.path }}</div>
              </div>
            </div>

            <!-- 上传（新建 / 替换）确认区：先选文件，再确认写入 -->
            <div v-if="skillImport" class="skill-import">
              <div class="skill-import-row">
                <span class="skill-import-file" :title="skillImport.name">{{ skillImport.name || '未选择文件' }}</span>
                <template v-if="skillImport.raw">
                  <span class="hint">{{ skillText(skillImport).length }} 字</span>
                  <el-select
                    :model-value="skillImport.mode"
                    size="small"
                    class="import-mode"
                    @change="reSkillMode"
                  >
                    <el-option label="整体导入" value="whole" />
                    <el-option label="自动识别" value="auto" />
                    <el-option label="仅取代码块" value="fence" />
                  </el-select>
                </template>
              </div>
              <div class="skill-import-row">
                <el-input
                  v-if="!skillImport.id"
                  v-model="skillImport.newId"
                  size="small"
                  class="skill-newid"
                  placeholder="技能名（小写字母开头）"
                />
                <span v-else class="hint">将替换 <b>{{ skillImport.id }}</b></span>
                <el-button
                  size="small"
                  type="primary"
                  :loading="skillSaving"
                  :disabled="!skillImport.raw"
                  @click="confirmSkillUpload"
                >
                  确认写入
                </el-button>
                <el-link type="info" :underline="false" class="skill-reload" @click="skillImport = null">取消</el-link>
              </div>
            </div>
          </section>
        </div>

        <div v-show="pane === 'category'">
      <!-- 分类管理 -->
        <div class="mgmt-head">
          <span class="hint">分类用于组织笔记 / 速查卡 / 资料，支持多级</span>
          <el-button type="primary" plain size="small" @click="addCategory(0)">＋ 新建顶级分类</el-button>
        </div>
        <el-table
          :data="categories"
          v-loading="catLoading"
          row-key="id"
          :tree-props="{ children: 'children' }"
          default-expand-all
          empty-text="还没有分类，点右上角新建一个吧"
          size="small"
        >
          <el-table-column prop="name" label="分类名称" min-width="160" />
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="addCategory(row.id)">＋ 子分类</el-button>
              <el-button link type="primary" size="small" @click="renameCategory(row)">改名</el-button>
              <el-button link type="danger" size="small" @click="removeCategory(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        </div>

        <div v-show="pane === 'tag'">
      <!-- 标签管理 -->
        <div class="mgmt-head">
          <span class="hint">标签可跨分类给笔记打标，一篇笔记可挂多个标签</span>
          <el-button type="primary" plain size="small" @click="addTag">＋ 新建标签</el-button>
        </div>
        <el-table :data="tags" v-loading="tagLoading" size="small" empty-text="还没有标签">
          <el-table-column prop="name" label="标签名称" min-width="160">
            <template #default="{ row }">
              <el-tag effect="plain" type="info"># {{ row.name }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="useCount" label="使用次数" width="90">
            <template #default="{ row }">{{ row.useCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="renameTag(row)">改名</el-button>
              <el-button link type="danger" size="small" @click="removeTag(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        </div>


        <!-- 隐藏的文件选择器：提示词导入 / 技能上传（必须留在模板里，按钮通过 ref 触发）-->
          <!-- 隐藏的文件选择器：现在只服务「对话提示词」的导入（导入目标由 pickPromptFile 记录） -->
          <input
            ref="fileInputRef"
            type="file"
            accept=".md,.markdown,.txt,text/markdown,text/plain"
            class="import-hidden"
            @change="onPromptFile"
          />

          <!-- 隐藏的文件选择器：技能上传（新建 / 替换 SKILL.md，目标由 skillImport.id 决定） -->
          <input
            ref="skillFileRef"
            type="file"
            accept=".md,.markdown,.txt,text/markdown,text/plain"
            class="import-hidden"
            @change="onSkillFile"
          />
      </div>
    </div>

    <template #footer>
      <el-button @click="resetAll" :disabled="saving">全部恢复默认</el-button>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 200px;
}

.sec-title {
  margin: 0 0 8px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--app-text-1);
  display: flex;
  align-items: center;
  gap: 8px;
}

.reset-link {
  font-size: 12px;
  margin-left: auto;
}

/* ---- 提示词导入工具栏 ---- */
.import-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}

/* 文件名可能很长：限宽 + 省略号，完整名走 title 悬浮查看 */
.import-file {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--app-text-1);
  font-weight: 600;
}

.import-mode {
  width: 118px;
}

.import-hidden {
  display: none;
}

/* ---- 技能：列表 + 上传（新建 / 替换 SKILL.md） ---- */
.sec-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

/* 联网开关的说明：一段话讲清"能干什么 + 花什么 + 安全边界" */
/* ---- 设置面板排版：左侧分组菜单 + 右侧单组内容 ---- */
.settings-body {
  display: grid;
  grid-template-columns: 148px minmax(0, 1fr);
  gap: 16px;
  /* 固定高度 + 内容区自己滚动：设置项再多也不会把弹窗撑到整屏 */
  height: 62vh;
}
.settings-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-right: 1px solid var(--app-border-weak);
  padding-right: 10px;
  overflow-y: auto;
}
.nav-item {
  border: 0;
  background: transparent;
  text-align: left;
  font-size: 13px;
  color: var(--app-text-2);
  padding: 7px 10px;
  border-radius: 7px;
  cursor: pointer;
  transition: all var(--dur-fast) ease;
}
.nav-item:hover {
  background: var(--app-bg);
  color: var(--app-text-1);
}
.nav-item.on {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
  font-weight: 600;
}
.settings-pane {
  overflow-y: auto;
  padding-right: 4px;
}
/* 分组内的 section 不再各占一大块：收紧间距与留白 */
.settings-pane .sec + .sec {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--app-border-weak);
}
/* 模型档案列表 */
.profile-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid var(--app-border-weak);
  border-radius: 8px;
  margin-bottom: 8px;
}
.profile-row.profile-active {
  border-color: color-mix(in srgb, var(--app-brand) 45%, transparent);
  background: var(--app-brand-soft);
}
.profile-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}
.profile-name {
  font-size: 13px;
  color: var(--app-text-1);
}
.profile-meta {
  font-size: 11.5px;
}
.profile-acts {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
/* 预设「＋服务商」按钮 */
.preset-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}
.preset-chip {
  border: 1px dashed var(--app-border);
  background: transparent;
  color: var(--app-text-2);
  font-size: 12px;
  padding: 5px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: all var(--dur-fast) ease;
}
.preset-chip:hover {
  color: var(--app-brand-deep);
  border-color: color-mix(in srgb, var(--app-brand) 45%, transparent);
  background: var(--app-brand-soft);
}
.preset-migrate {
  border-style: solid;
}
/* 档案编辑表单 */
.form-grid {
  display: grid;
  grid-template-columns: 84px 1fr;
  align-items: center;
  gap: 10px 12px;
}
.form-grid .fl {
  font-size: 12.5px;
  color: var(--app-text-2);
  text-align: right;
}
/* 模型分工表 */
.route-row {
  /* 用 grid 而不是 flex：右侧下拉的宽度必须由**容器**决定，不能被左侧文字长度牵着走。
     原来 flex + 下拉 224px 时，指示文字最长的那行（实体/概念页编译，460px）
     会把下拉**挤窄**成 200px —— 右边界还贴齐，所以看起来像"往右缩进了一下"（实测数据：
     多数行 selLeft=897/宽224，那一行 selLeft=921/宽200）。grid 之后列宽恒定。 */
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--app-border-weak);
}
/* 下拉填满它那一列（宽度由上面的列宽统一决定） */
.route-row :deep(.el-select) {
  width: 100%;
}
.route-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.route-label {
  font-size: 13px;
  color: var(--app-text-1);
}
.net-hint {
  margin: 8px 0 0;
  line-height: var(--lh-body);
}
.net-hint b {
  color: var(--app-text-1);
}

.skill-reload {
  font-size: 12px;
}

.skill-empty {
  padding: 10px 12px;
  border: 1px dashed var(--app-border);
  border-radius: var(--radius-sm);
  background: var(--app-bg);
}

.skill-empty .hint {
  margin: 0;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.skill-item {
  padding: 10px 12px;
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
  background: var(--app-bg);
}

.skill-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.skill-name {
  font-size: 13px;
  font-weight: 600;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  color: var(--app-text-1);
}

.skill-meta {
  margin-left: auto;
}

/* 路径可能很长（Windows 盘符 + 多级目录）：限宽省略，完整路径走 title */
.skill-path {
  margin-top: 6px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11.5px;
  color: var(--app-text-3);
}

.skill-import {
  margin-top: var(--space-sm);
  padding: 10px 12px;
  border: 1px solid var(--app-brand);
  border-radius: var(--radius-sm);
}

.skill-import-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.skill-import-row + .skill-import-row {
  margin-top: var(--space-sm);
}

/* 文件名可能很长：限宽 + 省略号，完整名走 title */
.skill-import-file {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-1);
}

.skill-newid {
  width: 200px;
}

.skill-empty code,
.hint code {
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--app-code-bg);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11.5px;
  color: var(--app-text-1);
}

.preset-select {
  width: 150px;
  margin-left: auto;
  flex: none;
}

/* ---- API 接入表单 ---- */
.ai-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.field-row {
  display: flex;
  gap: 18px;
}

.field.grow {
  flex: 1 1 0;
}

.lbl {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--app-text-2);
}

.lbl .reset-link {
  margin-left: auto;
}

.inline {
  display: flex;
  align-items: center;
  gap: 10px;
}

.unit {
  font-size: 12px;
  color: var(--app-text-3);
  white-space: nowrap;
}

.temp-slider {
  flex: 1 1 auto;
  margin: 0 4px;
}

.effort-select {
  width: 190px;
  flex: none;
}

/* ---- 已下线模型告警 ---- */
.warn-line {
  margin-top: 6px;
}

.warn-line :deep(.el-alert) {
  padding: 6px 10px;
  align-items: center;
}

.warn-line :deep(.el-alert__title) {
  font-size: 12.5px;
  line-height: 1.6;
}

.warn-text {
  color: inherit;
}

.warn-fix {
  margin-left: 8px;
  font-size: 12.5px;
  vertical-align: baseline;
}

.ai-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.ai-actions .hint {
  flex: 1 1 220px;
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--app-text-3);
}

.mgmt-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

:deep(.el-textarea__inner),
:deep(.el-select__wrapper) {
  font-family: inherit;
}
</style>