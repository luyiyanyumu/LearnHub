<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AgentPanel from '../components/AgentPanel.vue'
import { modelApi } from '../api'

/**
 * 智能体（左侧主导航的第 6 个版块）：**独立会话侧栏 + 对话区**。
 *
 * 为什么会话要单独一栏，而不是塞在对话面板顶部：
 * 会话是这一页的主对象（"我聊过哪几次、哪次到哪了、这次想固定用哪个模型"），
 * 塞在面板里只能折叠着看，列表一长就没法用。分栏之后左边管"选哪次"、
 * 右边管"这次聊什么"，和参考的 CH-agent 那种结构一致。
 *
 * 与 {@link AgentPanel} 的分工：面板只负责**对话**（工具调用、待确认写操作、
 * 保存为笔记、流式事件那一千多行）；会话的增删改查与高亮由这一页负责。
 * 两边通过 window 事件通信（`lh-agent-switch` 过去、`lh-agent-current` 回来），
 * 这样面板既能被抽屉复用、也能被本页复用，不必为了共享状态再造一层 store。
 */
const sessions = ref([])
const profiles = ref([])
const activeId = ref('')
const loading = ref(false)
/** 当前会话用的模型档案 id（空 = 用默认：当前生效档案） */
const activeProfile = ref('')
const creating = ref(false)

const providerOf = (id) => profiles.value.find((p) => p.id === id)

const emptyText = computed(() => (loading.value ? '正在读取会话…' : '选择或新建一个会话开始对话'))

async function loadAll() {
  loading.value = true
  try {
    const [s, m] = await Promise.all([modelApi.sessions(100), modelApi.profiles()])
    sessions.value = s || []
    profiles.value = m?.profiles || []
    // 默认选中最近一次会话（与面板里的 localStorage 一致时高亮才对得上）
    const saved = localStorage.getItem('lh-agent-session')
    if (saved && sessions.value.some((x) => x.id === saved)) {
      activeId.value = saved
    } else if (sessions.value.length && !activeId.value) {
      activeId.value = sessions.value[0].id
    }
    const cur = sessions.value.find((x) => x.id === activeId.value)
    activeProfile.value = cur?.modelProfileId || ''
  } catch (e) {
    sessions.value = []
  } finally {
    loading.value = false
  }
}

/** 选中一个会话：通知面板切过去 */
function select(id) {
  if (id === activeId.value) {
    return
  }
  activeId.value = id
  const cur = sessions.value.find((x) => x.id === id)
  activeProfile.value = cur?.modelProfileId || ''
  localStorage.setItem('lh-agent-session', id)
  window.dispatchEvent(new CustomEvent('lh-agent-switch', { detail: { id } }))
}

/** 新建会话：建完立刻选中（空会话也要能进去问第一句） */
async function createSession() {
  creating.value = true
  try {
    const s = await modelApi.newSession({ title: '新对话', modelProfileId: activeProfile.value || undefined })
    await loadAll()
    select(s.id)
    ElMessage.success('已新建会话')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    creating.value = false
  }
}

async function removeSession(s, e) {
  e?.stopPropagation()
  try {
    await ElMessageBox.confirm(
      `删除会话「${s.title || '新对话'}」及其全部消息？<br><br><span style="color:#6b7280">· 只删这次对话的记录，笔记/资料不受影响</span>`,
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', dangerouslyUseHTMLString: true }
    )
  } catch {
    return
  }
  try {
    await modelApi.removeSession(s.id)
    ElMessage.success('已删除')
    if (s.id === activeId.value) {
      activeId.value = ''
      activeProfile.value = ''
      localStorage.removeItem('lh-agent-session')
      // 面板还停在已删除的会话上：清掉它，避免继续往里发消息
      window.dispatchEvent(new CustomEvent('lh-agent-switch', { detail: { id: '' } }))
    }
    await loadAll()
  } catch (err) {
    /* 拦截器已提示 */
  }
}

/** 会话换模型：写库 + 通知面板 */
async function setModel(profileId) {
  if (!activeId.value) {
    return
  }
  try {
    await modelApi.setSessionModel(activeId.value, profileId || '')
    activeProfile.value = profileId || ''
    const cur = sessions.value.find((x) => x.id === activeId.value)
    if (cur) {
      cur.modelProfileId = profileId || null
    }
    ElMessage.success(profileId ? '本会话已固定使用该模型' : '本会话已改为默认（当前生效档案）')
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 面板回报"当前是哪条会话"，用于同步高亮（例如你在抽屉里换了会话） */
function onCurrent(e) {
  const id = e?.detail?.id
  if (id && id !== activeId.value) {
    activeId.value = id
    activeProfile.value = e.detail.modelProfileId || ''
  }
}

onMounted(() => {
  loadAll()
  window.addEventListener('lh-agent-current', onCurrent)
  // 面板第一次提问会自动建会话：建完要出现在侧栏里
  window.addEventListener('lh-agent-sessions-changed', loadAll)
})

onBeforeUnmount(() => {
  window.removeEventListener('lh-agent-current', onCurrent)
  window.removeEventListener('lh-agent-sessions-changed', loadAll)
})
</script>

<template>
  <div class="page agent-page">
    <div class="head">
      <div>
        <h2 class="page-h2">智能体</h2>
        <p class="head-sub">每次对话都是一个可保存的会话；新开、切换、删除、各自选模型，都在这里。</p>
      </div>
    </div>

    <div class="agent-layout">
      <!-- 会话侧栏 -->
      <aside class="sess-side">
        <div class="side-head">
          <span class="side-title">会话</span>
          <el-button size="small" type="primary" :loading="creating" @click="createSession">新建</el-button>
        </div>

        <div class="side-list" v-loading="loading">
          <div
            v-for="s in sessions"
            :key="s.id"
            class="side-row"
            :class="{ on: s.id === activeId }"
            @click="select(s.id)"
          >
            <div class="row-main">
              <span class="row-title" :title="s.title">{{ s.title || '新对话' }}</span>
              <span class="row-meta">
                <span class="row-tag">{{ providerOf(s.modelProfileId)?.name || '默认（当前生效档案）' }}</span>
                {{ s.events }} 条 · {{ s.updatedAt ? s.updatedAt.slice(5, 16) : '' }}
              </span>
            </div>
            <button type="button" class="row-del" title="删除这个会话及其全部消息" @click="removeSession(s, $event)">✕</button>
          </div>
          <p v-if="!sessions.length && !loading" class="hint side-empty">
            还没有会话 —— 点「新建」，或在右边直接问一句
          </p>
        </div>

        <div class="side-foot">
          <span class="hint">
            共 {{ sessions.length }} 次会话
            <template v-if="activeId"> · 本会话模型：{{ providerOf(activeProfile)?.name || '默认（当前生效档案）' }}</template>
          </span>
          <el-select
            v-if="activeId"
            :model-value="activeProfile"
            size="small"
            style="width: 100%; margin-top: 6px"
            placeholder="默认（当前生效档案）"
            @change="setModel"
          >
            <el-option label="默认（当前生效档案）" value="" />
            <el-option
              v-for="p in profiles"
              :key="p.id"
              :label="p.name + '（' + p.model + '）'"
              :value="p.id"
            />
          </el-select>
        </div>
      </aside>

      <!-- 对话区 -->
      <section class="chat-area">
        <AgentPanel v-if="activeId" embedded :show-session-bar="false" />
        <div v-else class="chat-empty">
          <p class="empty-t">{{ emptyText }}</p>
          <p class="hint">会话会存在库里：刷新页面、重启后端，回来都还在。</p>
          <el-button size="small" type="primary" :loading="creating" @click="createSession">新建一个会话</el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 整页：标题固定，下面两栏吃掉剩余高度（与知识图谱页同一套做法） */
.page.agent-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  box-sizing: border-box;
  overflow: hidden;
}
.agent-layout {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: 268px minmax(0, 1fr);
  gap: 12px;
}

/* ---- 会话侧栏 ---- */
.sess-side {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  background: var(--app-card);
  overflow: hidden;
}
.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--app-border-weak);
}
.side-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-1);
}
.side-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 6px;
}
.side-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background var(--dur-fast) ease;
}
.side-row + .side-row {
  margin-top: 2px;
}
.side-row:hover {
  background: var(--app-bg);
}
.side-row.on {
  background: var(--app-brand-soft);
}
.row-main {
  min-width: 0;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.row-title {
  font-size: 12.5px;
  color: var(--app-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.side-row.on .row-title {
  color: var(--app-brand-deep);
  font-weight: 600;
}
.row-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--app-text-3);
  overflow: hidden;
  white-space: nowrap;
}
/* 模型档案标签：和参考界面一样，一眼看出这条会话用哪个模型 */
.row-tag {
  flex-shrink: 0;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  color: var(--app-text-2);
}
.side-row.on .row-tag {
  border-color: color-mix(in srgb, var(--app-brand) 40%, transparent);
}
.row-del {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--app-text-3);
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
  visibility: hidden;
}
.side-row:hover .row-del,
.side-row.on .row-del {
  visibility: visible;
}
.row-del:hover {
  background: color-mix(in srgb, #dc2626 14%, transparent);
  color: #dc2626;
}
.side-empty {
  padding: 8px 6px;
}
.side-foot {
  padding: 8px 10px;
  border-top: 1px solid var(--app-border-weak);
}

/* ---- 对话区 ---- */
.chat-area {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--app-card);
}
.chat-empty {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
}
.empty-t {
  margin: 0;
  font-size: 14px;
  color: var(--app-text-2);
}

/* 嵌入面板铺满对话区：面板默认承载方式是"固定右侧 480px 抽屉"，这一页不要那个形状。
   用 :deep 从父级钉死，避免依赖两处 CSS 的优先级先后 */
.chat-area :deep(.agent-root),
.chat-area :deep(.agent-dock),
.chat-area :deep(.agent-dock.embedded) {
  position: static;
  right: auto;
  bottom: auto;
  width: 100%;
  height: 100%;
  transform: none;
  border-left: 0;
  box-shadow: none;
  pointer-events: auto;
  z-index: auto;
}
.chat-area :deep(.panel) {
  width: 100%;
  height: 100%;
}
</style>
