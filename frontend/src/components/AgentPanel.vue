<script setup>
import { defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi, categoryApi, noteApi } from '../api'
import { fixHtmlQuotes } from '../utils/htmlQuotes'
import { isDark } from '../composables/useTheme'

/**
 * Markdown 预览（md-editor-v3）体积很大：整包 + 样式约 310 KB，
 * 而它只在「抽屉第一次打开 / 保存对话框第一次打开」时才用得到。
 *
 * 做成异步组件后，动态 import 是在组件**实例化**时才触发的 ——
 * el-drawer / el-dialog 的内容都是首次打开才渲染，所以首屏完全不会下载它。
 *
 * 加载顺序很重要：mdEditorSetup 里才会注册 markdown-it 的 ::: 提示块扩展，
 * 必须先 await 它，再返回 MdPreview，否则第一批渲染的 ::: 块认不出来。
 */
const MdPreview = defineAsyncComponent(() =>
  Promise.all([import('../utils/mdEditorSetup'), import('md-editor-v3')]).then(([, m]) => m.MdPreview),
)

const router = useRouter()

const open = ref(false)
const configured = ref(true)
const busy = ref(false)
const input = ref('')
const listRef = ref(null)
const messages = ref([])

const SUGGESTIONS = [
  'Java 的 == 和 equals 有什么区别？',
  'MyBatis-Plus 分页怎么写？',
  '这段代码帮我逐行讲讲',
  'MQTT 消息会丢吗？',
]

function scrollBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

async function loadStatus() {
  try {
    const s = await aiApi.status()
    configured.value = s.configured
  } catch (e) {
    configured.value = false
  }
}

async function send(text) {
  const msg = (text ?? input.value).trim()
  if (!msg || busy.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: msg })
  scrollBottom()
  busy.value = true
  const holder = { role: 'assistant', loading: true, content: '' }
  messages.value.push(holder)
  scrollBottom()
  try {
    // 历史 = 当前提问之前的所有消息。
    // 注意：上面的 filter 已经把 loading 占位（assistant 且 content 为空）滤掉了，
    // 所以要丢的只有「刚 push 的这条 user 消息」本身 → slice(0, -1)。
    // 曾写成 slice(0, -2)：那是按「filter 还没滤掉 loading」想的，结果连上一条回答
    // 一起删了，追问「上面第二点展开讲」时 AI 完全看不到自己上次说了什么。
    const history = messages.value
      .filter((m) => m.role === 'user' || (m.role === 'assistant' && m.content))
      .slice(0, -1)
      .map((m) => ({ role: m.role, content: m.content }))
    const res = await aiApi.chat({ message: msg, history })
    holder.loading = false
    holder.content = res.reply || ''
    holder.events = res.events || []
    holder.toolUsed = res.toolUsed
    holder.id = holder.id || Date.now()
    configured.value = true
  } catch (e) {
    holder.loading = false
    holder.content = ''
    holder.error = e.message || '请求失败'
  } finally {
    busy.value = false
    scrollBottom()
  }
}

/** 保存为笔记（每次回答后由用户决定） */
const saveVisible = ref(false)
const saveBusy = ref(false)
const saveForm = ref({ title: '', categoryId: undefined, content: '' })
const categories = ref([])

async function askSave(holder) {
  if (!holder || !holder.content) return
  categories.value = await loadCategories()
  saveForm.value = {
    title: guessTitle(holder.content),
    categoryId: undefined,
    content: holder.content,
    sourceId: holder.id,
  }
  saveVisible.value = true
}

function guessTitle(md) {
  const lines = (md || '').split('\n')
  for (const line of lines) {
    const m = line.match(/^\s*#\s+(.+)$/)
    if (m) return m[1].trim().slice(0, 60)
  }
  const plain = (md || '').replace(/[#*`>\[\]()]/g, '').replace(/\s+/g, ' ').trim()
  return plain.slice(0, 40) || '智能体回答'
}

async function loadCategories() {
  const tree = await categoryApi.tree()
  const flat = []
  ;(function walk(nodes, depth = 0) {
    for (const n of nodes || []) {
      flat.push({ ...n, depth })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  })(tree)
  return flat
}

async function doSaveNote() {
  if (!saveForm.value.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  saveBusy.value = true
  try {
    const created = await noteApi.add({
      title: saveForm.value.title.trim(),
      content: saveForm.value.content,
      categoryId: saveForm.value.categoryId || null,
      tagIds: [],
    })
    saveVisible.value = false
    const holder = messages.value.find((m) => m.id === saveForm.value.sourceId)
    if (holder) holder.saved = true
    ElMessageBox.confirm(`已保存为笔记「${created.title}」，现在去看看？`, '保存成功', {
      confirmButtonText: '去看看',
      cancelButtonText: '继续提问',
      type: 'success',
    })
      .then(() => {
        open.value = false
        router.push(`/notes/${created.id}`)
      })
      .catch(() => {})
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saveBusy.value = false
  }
}

/** 整段回答一键复制 */
async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text || '')
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

onMounted(() => {
  loadStatus()
  // 支持从笔记编辑器等页面唤起（window 事件，避免组件强耦合）
  window.addEventListener('lh-agent-open', openFromEvent)
})

onBeforeUnmount(() => {
  window.removeEventListener('lh-agent-open', openFromEvent)
})

function openFromEvent() {
  open.value = true
}
</script>

<template>
  <div class="agent-root">
    <!-- 悬浮入口 -->
    <transition name="fab">
      <button v-if="!open" class="fab" type="button" @click="open = true">
        <span class="fab-dot"></span>
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
          <rect x="4" y="4" width="16" height="13" rx="3"></rect>
          <path d="M8 21h8M12 17v4"></path>
          <path d="M8.5 10.5v3M12 9v4.5M15.5 11.5v2"></path>
        </svg>
        <span class="fab-label">智能体</span>
        <span v-if="!configured" class="fab-warn" title="未配置 API Key">!</span>
      </button>
    </transition>

    <!-- 右侧抽屉 -->
    <el-drawer v-model="open" direction="rtl" size="480px" :with-header="false" class="agent-drawer">
      <div class="panel">
        <header class="panel-head">
          <div class="head-left">
            <span class="head-avatar">A</span>
            <div>
              <div class="head-title">智能体助手</div>
              <div class="head-sub">
                <span class="dot" :class="configured ? 'on' : 'off'"></span>
                {{ configured ? '代码答疑 · 可帮你沉淀笔记' : '未配置 API Key' }}
              </div>
            </div>
          </div>
          <button class="icon-btn" type="button" @click="open = false" title="收起">✕</button>
        </header>

        <div v-if="!configured" class="cfg-tip">
          智能体未启用：请在 <code>backend/src/main/resources/application.yml</code> 的
          <code>ai.deepseek.api-key</code> 填入 API Key（或设置环境变量
          <code>DEEPSEEK_API_KEY</code>）并重启后端。
        </div>

        <div ref="listRef" class="msg-list">
          <div v-if="!messages.length" class="welcome">
            <p class="welcome-t">你好，我是你的代码学习搭子 🤖</p>
            <p class="welcome-s">可以问我任何编程/IT 问题；讲到值得沉淀的知识点，点「保存为笔记」就能存进工作台。</p>
            <div class="chips">
              <button v-for="s in SUGGESTIONS" :key="s" type="button" class="chip" @click="send(s)">{{ s }}</button>
            </div>
          </div>

          <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
            <template v-if="m.role === 'assistant'">
              <div class="avatar av-ai">A</div>
              <div class="bubble assistant">
                <div v-if="m.loading" class="typing">
                  <span></span><span></span><span></span>
                </div>
                <div v-else-if="m.error" class="err">{{ m.error }}</div>
                <template v-else>
                  <!-- 工具事件角标 -->
                  <div v-if="m.events?.length" class="evt-list">
                    <span v-for="(ev, j) in m.events" :key="j" class="evt">{{ ev }}</span>
                  </div>
                  <div class="md-body"><MdPreview :modelValue="fixHtmlQuotes(m.content || '')" :theme="isDark ? 'dark' : 'light'" previewTheme="github" /></div>
                  <!-- 每次回答后：询问是否沉淀 -->
                  <div v-if="m.content && !m.saved" class="msg-actions">
                    <button type="button" class="act-btn primary" @click="askSave(m)">📌 保存为笔记</button>
                    <button type="button" class="act-btn" @click="copyText(m.content)">复制</button>
                  </div>
                  <div v-else-if="m.saved" class="saved-tag">✓ 已保存为笔记</div>
                </template>
              </div>
            </template>
            <div v-else class="bubble user">{{ m.content }}</div>
          </div>
        </div>

        <footer class="panel-foot">
          <div class="input-row">
            <input
              v-model="input"
              class="chat-input"
              placeholder="问我代码问题，或说「把 xxx 记成笔记」…"
              :disabled="busy"
              @keydown.enter.prevent="send()"
            />
            <button class="send-btn" type="button" :disabled="busy || !input.trim()" @click="send()">
              <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M22 2 11 13M22 2l-7 20-4-9-9-4Z"></path>
              </svg>
            </button>
          </div>
          <p class="foot-hint">AI 生成内容仅供参考 · 拥有 6 种工具：建/改笔记、查笔记、建速查卡、查分类</p>
        </footer>
      </div>
    </el-drawer>

    <!-- 保存为笔记对话框 -->
    <el-dialog v-model="saveVisible" title="📌 保存为笔记" width="560px" top="12vh" destroy-on-close>
      <div class="save-form">
        <el-input v-model="saveForm.title" placeholder="笔记标题" maxlength="120" />
        <el-select v-model="saveForm.categoryId" placeholder="选择分类（可选）" clearable style="width: 100%">
          <el-option v-for="c in categories" :key="c.id" :label="'　'.repeat(c.depth) + c.name" :value="c.id" />
        </el-select>
        <div class="save-preview">
          <MdPreview :modelValue="fixHtmlQuotes(saveForm.content || '*内容为空*')" :theme="isDark ? 'dark' : 'light'" previewTheme="github" />
        </div>
      </div>
      <template #footer>
        <el-button @click="saveVisible = false">取消</el-button>
        <el-button type="primary" :loading="saveBusy" @click="doSaveNote">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.agent-root {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 60;
}

/* 悬浮按钮 */
.fab {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 46px;
  padding: 0 16px 0 12px;
  border: none;
  border-radius: 999px;
  background: var(--app-brand);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 24px var(--app-brand-glow);
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.fab:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 28px var(--app-brand-glow);
}
.fab-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #fff;
  opacity: 0.9;
}
.fab-warn {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #ffd166;
  color: #7a5a00;
  font-size: 11px;
  font-weight: 700;
  display: grid;
  place-items: center;
}

/* 抽屉容器调整 */
:deep(.el-drawer) {
  background: var(--app-card);
}
.panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--app-border);
}
.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.head-avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--app-brand), var(--app-brand-2, var(--app-brand)));
  color: #fff;
  font-weight: 700;
  display: grid;
  place-items: center;
}
.head-title {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--app-text-1);
}
.head-sub {
  font-size: 11.5px;
  color: var(--app-text-3);
  display: flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}
.dot.on {
  background: #22c55e;
}
.dot.off {
  background: #f59e0b;
}
.icon-btn {
  border: none;
  background: transparent;
  color: var(--app-text-3);
  cursor: pointer;
  font-size: 14px;
  width: 28px;
  height: 28px;
  border-radius: 7px;
}
.icon-btn:hover {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
}

.cfg-tip {
  margin: 10px 14px 0;
  padding: 10px 12px;
  border-radius: 9px;
  background: #fff7e6;
  border: 1px solid #ffe0a3;
  color: #8a5a00;
  font-size: 12px;
  line-height: 1.7;
}
html.dark .cfg-tip {
  background: rgba(255, 209, 102, 0.12);
  border-color: rgba(255, 209, 102, 0.35);
  color: #ffd166;
}
.cfg-tip code {
  background: rgba(0, 0, 0, 0.06);
  padding: 0 4px;
  border-radius: 4px;
  font-size: 11.5px;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.welcome {
  color: var(--app-text-3);
  font-size: 13px;
}
.welcome-t {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text-1);
  margin-bottom: 6px;
}
.welcome-s {
  line-height: 1.7;
  margin-bottom: 12px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  border: 1px solid var(--app-border);
  background: var(--app-bg);
  color: var(--app-text-2);
  font-size: 12px;
  font-family: inherit;
  padding: 6px 11px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
  text-align: left;
}
.chip:hover {
  border-color: var(--app-brand);
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
}

.msg {
  display: flex;
  gap: 8px;
}
.msg.user {
  justify-content: flex-end;
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  margin-top: 2px;
}
.av-ai {
  background: var(--app-brand);
}
.bubble {
  max-width: 82%;
  padding: 10px 12px;
  border-radius: 12px;
  font-size: 13.5px;
  line-height: 1.7;
  word-break: break-word;
}
.bubble.assistant {
  background: var(--app-bg);
  border: 1px solid var(--app-border);
  color: var(--app-text-1);
}
.bubble.user {
  background: var(--app-brand);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.bubble.assistant .md-body :deep(.md-editor-preview) {
  background: transparent;
}

.evt-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-bottom: 8px;
}
.evt {
  font-size: 11px;
  color: var(--app-brand-deep);
  background: var(--app-brand-soft);
  padding: 3px 8px;
  border-radius: 999px;
}

.typing {
  display: flex;
  gap: 4px;
  padding: 3px 0;
}
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-text-3);
  animation: blink 1.2s infinite;
}
.typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%, 80%, 100% {
    opacity: 0.25;
  }
  40% {
    opacity: 1;
  }
}
.err {
  color: #e53e3e;
  font-size: 13px;
}

.msg-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--app-border);
}
.act-btn {
  border: 1px solid var(--app-border);
  background: var(--app-card);
  color: var(--app-text-2);
  font-size: 12px;
  font-family: inherit;
  padding: 4px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.act-btn:hover {
  border-color: var(--app-brand);
}
.act-btn.primary {
  background: var(--app-brand);
  border-color: var(--app-brand);
  color: #fff;
  font-weight: 500;
}
.saved-tag {
  margin-top: 8px;
  font-size: 12px;
  color: #16a34a;
}

.panel-foot {
  border-top: 1px solid var(--app-border);
  padding: 12px 14px 14px;
}
.input-row {
  display: flex;
  gap: 8px;
}
.chat-input {
  flex: 1;
  height: 38px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-bg);
  color: var(--app-text-1);
  padding: 0 12px;
  font-size: 13.5px;
  font-family: inherit;
  outline: none;
  transition: border-color 0.15s ease;
}
.chat-input:focus {
  border-color: var(--app-brand);
}
.chat-input::placeholder {
  color: var(--app-text-3);
}
.send-btn {
  width: 38px;
  height: 38px;
  border: none;
  border-radius: 10px;
  background: var(--app-brand);
  color: #fff;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: opacity 0.15s ease;
}
.send-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.foot-hint {
  margin-top: 8px;
  font-size: 11px;
  color: var(--app-text-3);
  text-align: center;
}

.save-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.save-preview {
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 8px 12px;
  max-height: 260px;
  overflow: auto;
  background: var(--app-bg);
  font-size: 13px;
}

/* 抽屉开关动画 */
.fab-enter-active,
.fab-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.fab-enter-from,
.fab-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
