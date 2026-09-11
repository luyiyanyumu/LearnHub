<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi, tagApi, settingsApi, aiApi } from '../api'
import { setThemeMode, themeMode } from '../composables/useTheme'

const visible = defineModel({ type: Boolean, default: false })

const activeTab = ref('ai')
const saving = ref(false)
const loading = ref(false)
const testing = ref(false)
const configuredModel = ref('')
const presetLabel = ref('')

// ---------- AI / 外观设置 ----------
/** AI 相关字段（顺序即 diff / 重置顺序） */
const AI_FIELDS = [
  'baseUrl', 'apiKey', 'model', 'maxTokens', 'temperature',
  'thinking', 'reasoningEffort', 'polishPrompt', 'formatPrompt',
]
const emptyForm = () => ({
  baseUrl: '', apiKey: '', model: '', maxTokens: '', temperature: '',
  thinking: '', reasoningEffort: '', polishPrompt: '', formatPrompt: '',
})

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
    label: '⚠️ 已失效（调用会返回 400，仅用于识别错误配置）',
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
    polishPrompt: !!s.polishOverridden,
    formatPrompt: !!s.formatOverridden,
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
  }
}, { immediate: true })

async function load() {
  loading.value = true
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
  <el-dialog v-model="visible" title="⚙ 设置" width="680px" top="6vh" destroy-on-close>
    <el-tabs v-model="activeTab">
      <!-- 外观与 AI -->
      <el-tab-pane label="外观与 AI" name="ai">
        <div v-loading="loading" class="settings">
          <section class="sec">
            <h4 class="sec-title">界面风格</h4>
            <el-radio-group v-model="themeMode" @change="(m) => setThemeMode(m)">
              <el-radio-button value="light">☀ 浅色</el-radio-button>
              <el-radio-button value="dark">🌙 深色</el-radio-button>
              <el-radio-button value="auto">🖥 跟随系统</el-radio-button>
            </el-radio-group>
            <p class="hint">切换即时预览；侧栏的太阳/月亮按钮也随时可切</p>
          </section>

          <section class="sec">
            <h4 class="sec-title">
              🔌 API 接入
              <el-tag v-if="overridden.baseUrl || overridden.apiKey || overridden.model" size="small" type="warning" effect="plain">已自定义</el-tag>
              <el-select
                v-model="presetLabel"
                class="preset-select"
                placeholder="快速填充服务商"
                size="small"
                @change="applyPreset"
              >
                <el-option v-for="p in PRESETS" :key="p.label" :label="p.label" :value="p.label" />
              </el-select>
            </h4>
            <div class="ai-form">
              <div class="field">
                <label class="lbl">
                  接口地址
                  <el-link v-if="overridden.baseUrl" type="primary" :underline="false" class="reset-link" @click="resetField('baseUrl')">恢复默认</el-link>
                </label>
                <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
              </div>
              <div class="field">
                <label class="lbl">
                  API Key
                  <el-tag v-if="clearApiKey" size="small" type="warning" effect="plain">保存后将清除</el-tag>
                  <el-tag v-else-if="hasApiKey" size="small" type="success" effect="plain">已保存</el-tag>
                  <el-link
                    v-if="hasApiKey && !clearApiKey"
                    type="primary"
                    :underline="false"
                    class="reset-link"
                    @click="clearSavedKey"
                  >清除已保存的密钥</el-link>
                </label>
                <el-input
                  v-model="form.apiKey"
                  type="password"
                  show-password
                  :placeholder="hasApiKey && !clearApiKey
                    ? '已保存密钥（出于安全不回显）：留空 = 不修改，要换直接粘贴新的'
                    : '留空 = 使用后端 .env 中的密钥'"
                />
                <p class="hint">密钥只存在本机数据库或 backend/.env，接口不会再把它回传到页面。</p>
              </div>
              <div class="field">
                <label class="lbl">
                  模型名称
                  <el-link v-if="overridden.model" type="primary" :underline="false" class="reset-link" @click="resetField('model')">恢复默认</el-link>
                </label>
                <el-select
                  v-model="form.model"
                  filterable
                  allow-create
                  default-first-option
                  placeholder="按服务商分组选择，或直接输入未列出的模型名"
                  style="width: 100%"
                >
                  <el-option-group v-for="g in MODEL_GROUPS" :key="g.label" :label="g.label">
                    <el-option
                      v-for="o in g.options"
                      :key="o.value"
                      :label="o.label"
                      :value="o.value"
                      :disabled="o.disabled"
                    />
                  </el-option-group>
                </el-select>
                <div v-if="modelIsDead || legacyReplacement" class="warn-line">
                  <el-alert
                    :type="modelIsDead ? 'error' : 'warning'"
                    :closable="false"
                    show-icon
                  >
                    <template #title>
                      <span class="warn-text">
                        <template v-if="modelIsDead">
                          「{{ form.model }}」已被官方拒收，调用会直接返回 400。
                        </template>
                        <template v-else>
                          「{{ form.model }}」是旧名——目前仍可用，但已不是官方文档里的主名。
                        </template>
                      </span>
                      <el-link
                        v-if="legacyReplacement"
                        type="primary"
                        :underline="false"
                        class="warn-fix"
                        @click="fixLegacyModel"
                      >
                        一键换成 {{ legacyReplacement }}
                      </el-link>
                    </template>
                  </el-alert>
                </div>
              </div>

              <div class="field">
                <label class="lbl">
                  思考模式
                  <el-link v-if="overridden.thinking" type="primary" :underline="false" class="reset-link" @click="resetField('thinking')">恢复默认</el-link>
                </label>
                <div class="inline">
                  <el-radio-group v-model="form.thinking" size="small">
                    <el-radio-button value="">自动</el-radio-button>
                    <el-radio-button value="enabled">开启</el-radio-button>
                    <el-radio-button value="disabled">关闭</el-radio-button>
                  </el-radio-group>
                  <el-select
                    v-model="form.reasoningEffort"
                    size="small"
                    class="effort-select"
                    :disabled="!thinkingOn"
                    placeholder="思考强度"
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
                  「自动」= 由模型决定：DeepSeek 的 flash / reasoner 与 V4 系列默认开启思考，deepseek-chat 默认不开。
                  思考强度仅对 DeepSeek 官方端点（api.deepseek.com）生效。
                  <b>润色 / 整理格式默认走快速通道（不思考）</b>——这类机械转换思考收益小、却要慢 3-4 倍；
                  只有把思考模式显式设为「开启」才会让它们也思考。
                </p>
              </div>
              <div class="field-row">
                <div class="field grow">
                  <label class="lbl">
                    最大输出
                    <el-link v-if="overridden.maxTokens" type="primary" :underline="false" class="reset-link" @click="resetField('maxTokens')">恢复默认</el-link>
                  </label>
                  <div class="inline">
                    <el-input v-model="form.maxTokens" placeholder="8192" style="width: 130px" />
                    <span class="unit">tokens</span>
                  </div>
                </div>
                <div class="field grow">
                  <label class="lbl">
                    温度
                    <el-tag v-if="!tempAllowed" size="small" type="info" effect="plain">当前不生效</el-tag>
                    <el-link v-if="overridden.temperature" type="primary" :underline="false" class="reset-link" @click="resetField('temperature')">恢复默认</el-link>
                  </label>
                  <div class="inline">
                    <el-slider
                      v-model="tempNum"
                      :min="0"
                      :max="2"
                      :step="0.1"
                      :disabled="!tempAllowed"
                      class="temp-slider"
                    />
                    <span class="unit">{{ tempNum.toFixed(1) }}</span>
                  </div>
                  <p v-if="!tempAllowed" class="hint">
                    {{
                      thinkingOn
                        ? '思考模式已开启：DeepSeek V4 等模型在思考时会忽略温度，请求里已不带该参数。'
                        : '该模型属于思考型且无法关闭思考，其接口要求不要下发温度参数。'
                    }}
                  </p>
                </div>
              </div>
            </div>
            <div class="ai-actions">
              <el-button size="small" :loading="testing" @click="testConn">⚡ 测试连接</el-button>
              <span class="hint">
                按上面填的地址/密钥/模型直接试一次，不必先保存
                <template v-if="configuredModel"> · 当前生效：{{ configuredModel }}</template>
              </span>
            </div>
          </section>

          <section class="sec">
            <h4 class="sec-title">
              ✨ 润色提示词
              <el-tag v-if="overridden.polishPrompt" size="small" type="warning" effect="plain">已自定义</el-tag>
              <el-link v-if="overridden.polishPrompt" type="primary" :underline="false" class="reset-link" @click="resetField('polishPrompt')">恢复默认</el-link>
            </h4>
            <el-input
              v-model="form.polishPrompt"
              type="textarea"
              :rows="6"
              placeholder="留空 = 使用内置默认。可自定义润色规则，如：统一术语、口语转书面、修正标点…"
            />
          </section>

          <section class="sec">
            <h4 class="sec-title">
              🧹 格式提示词
              <el-tag v-if="overridden.formatPrompt" size="small" type="warning" effect="plain">已自定义</el-tag>
              <el-link v-if="overridden.formatPrompt" type="primary" :underline="false" class="reset-link" @click="resetField('formatPrompt')">恢复默认</el-link>
            </h4>
            <el-input
              v-model="form.formatPrompt"
              type="textarea"
              :rows="6"
              placeholder="留空 = 使用内置默认。可自定义排版规则，如：标题层级、列表、代码块标注语言…"
            />
          </section>
        </div>
      </el-tab-pane>

      <!-- 分类管理 -->
      <el-tab-pane label="分类" name="category">
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
      </el-tab-pane>

      <!-- 标签管理 -->
      <el-tab-pane label="标签" name="tag">
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
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <template v-if="activeTab === 'ai'">
        <el-button @click="resetAll" :disabled="saving">全部恢复默认</el-button>
        <el-button @click="visible = false">关闭</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
      <el-button v-else @click="visible = false">关闭</el-button>
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
