<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { codeApi } from '../api'

/**
 * 代码库（左侧导航第 7 个版块）。
 *
 * 两个层级，像文件管理器：
 *   ① **项目层**：所有项目直接铺在页面上（名字、段数、语言构成、仓库/演示链接），点进去看；
 *      另有一个「未归类」入口，放不属于任何项目的片段。
 *   ② **片段层**：某个项目内的片段列表 + 检索（检索自动限定在当前项目内）。
 *
 * 为什么不做成"右上角一个项目按钮"：项目是这块的主要内容，藏进按钮里等于每次多点一次；
 * 铺在页面上才能一眼看到"我有哪几个仓库、各有多少代码"。
 */
const view = ref('projects')          // projects | snippets
const currentRepo = ref(null)         // 当前项目；{id:null,name:'未归类'} 表示未归类
const searching = ref(false)          // 片段层是否处于"全局搜索结果"状态

const mode = ref('all')
const keyword = ref('')
const langFilter = ref('')
const rows = ref([])
const repos = ref([])
const stats = ref({ snippets: 0, repos: 0, symbols: 0, langs: [] })
const loading = ref(false)

const detail = ref(null)
const detailLoading = ref(false)
const form = ref(null)
const saving = ref(false)
const repoForm = ref(null)

const folderInput = ref(null)
const importing = ref(false)
const importProgress = ref('')

const LANGS = ['java', 'python', 'js', 'ts', 'vue', 'go', 'sql', 'shell', 'yaml', '其他']
const LIST_LIMIT = 500

const modeHint = computed(() => ({
  symbol: '按符号名精确/前缀匹配，命中给出所在行 —— 问"在哪定义"用这个',
  keyword: '按标题 / 说明 / 标识符 / 正文模糊匹配 —— 问"我写过什么"用这个',
  all: '先按符号找，再按关键词补 —— 默认（推荐）',
}[mode.value] || ''))

const repoById = computed(() => Object.fromEntries(repos.value.map((r) => [r.id, r])))
const orphanCount = computed(() => {
  const total = Number(stats.value.snippets || 0)
  const assigned = repos.value.reduce((s, r) => s + Number(r.snippets || 0), 0)
  return Math.max(0, total - assigned)
})
const pageTitle = computed(() => {
  if (view.value === 'projects') {
    return '代码库'
  }
  return searching.value ? `搜索结果` : (currentRepo.value?.name || '全部片段')
})

async function loadAll() {
  try {
    const [s, r] = await Promise.all([codeApi.stats(), codeApi.repos()])
    stats.value = s || { snippets: 0, repos: 0, symbols: 0, langs: [] }
    repos.value = r || []
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 进入某个项目（或"未归类"） */
async function enterRepo(repo) {
  currentRepo.value = repo
  searching.value = false
  view.value = 'snippets'
  keyword.value = ''
  langFilter.value = ''
  detail.value = null
  await runSearch()
}

function backToProjects() {
  view.value = 'projects'
  currentRepo.value = null
  searching.value = false
  keyword.value = ''
  rows.value = []
  detail.value = null
  loadAll()
}

async function runSearch() {
  loading.value = true
  try {
    const params = { mode: mode.value, limit: LIST_LIMIT }
    if (langFilter.value) {
      params.lang = langFilter.value
    }
    const q = keyword.value.trim()
    if (view.value === 'projects') {
      // 项目层检索 = 全库检索（此时没有"当前项目"的概念）
      searching.value = true
      view.value = 'snippets'
      currentRepo.value = null
    } else if (!searching.value && currentRepo.value) {
      // 项目内检索：限定在本项目
      if (currentRepo.value.id == null) {
        params.unassigned = true
      } else {
        params.repoId = currentRepo.value.id
      }
    }
    if (q) {
      rows.value = await codeApi.search(q, params)
    } else if (searching.value && !currentRepo.value) {
      rows.value = await codeApi.snippets(params)
    } else {
      rows.value = await codeApi.snippets(params)
    }
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  keyword.value = ''
  langFilter.value = ''
  runSearch()
}

async function openDetail(row) {
  detailLoading.value = true
  detail.value = row
  try {
    detail.value = await codeApi.snippet(row.id)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    detailLoading.value = false
  }
}

function openForm(row) {
  if (row) {
    form.value = { ...row, code: '', explainText: '' }
    codeApi.snippet(row.id).then((d) => {
      if (form.value && form.value.id === d.id) {
        form.value = { ...form.value, ...d }
      }
    })
  } else {
    form.value = {
      id: null, title: '', lang: 'java', code: '', explainText: '',
      filePath: '', sourceUrl: '', repoId: currentRepo.value?.id || null,
    }
  }
}

async function saveForm() {
  const f = form.value
  if (!f.title?.trim()) {
    ElMessage.warning('标题要填')
    return
  }
  if (!f.code?.trim()) {
    ElMessage.warning('代码不能为空')
    return
  }
  saving.value = true
  try {
    await codeApi.saveSnippet(f.id, {
      title: f.title.trim(), lang: f.lang, code: f.code,
      explainText: f.explainText || '', filePath: f.filePath || '',
      sourceUrl: f.sourceUrl || '', repoId: f.repoId || null,
    })
    ElMessage.success(f.id ? '已保存（符号索引已重建）' : '已加入代码库（已建符号索引）')
    form.value = null
    await loadAll()
    await runSearch()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function removeSnippet(row, e) {
  e?.stopPropagation()
  try {
    await ElMessageBox.confirm(`删除代码片段「${row.title}」及其符号索引？`, '删除', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await codeApi.removeSnippet(row.id)
    if (detail.value?.id === row.id) {
      detail.value = null
    }
    ElMessage.success('已删除')
    await loadAll()
    await runSearch()
  } catch (err) {
    /* 拦截器已提示 */
  }
}

function openRepoForm(row) {
  repoForm.value = row ? { ...row } : { id: null, name: '', url: '', demoUrl: '', license: '', note: '' }
}

async function saveRepoForm() {
  const f = repoForm.value
  if (!f.name?.trim()) {
    ElMessage.warning('项目名要填')
    return
  }
  try {
    await codeApi.saveRepo(f.id, { ...f })
    ElMessage.success('已保存')
    repoForm.value = null
    await loadAll()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function removeRepo(row) {
  try {
    await ElMessageBox.confirm(
      `删除项目「${row.name}」？<br><br><span style="color:#6b7280">· 它下面的代码片段**不会被删**，只会变成"未归类"</span>`,
      '删除项目',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', dangerouslyUseHTMLString: true },
    )
  } catch {
    return
  }
  try {
    await codeApi.removeRepo(row.id)
    ElMessage.success('已删除')
    await loadAll()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function copyCode(text) {
  try {
    await navigator.clipboard.writeText(text || '')
    ElMessage.success('已复制代码')
  } catch (e) {
    ElMessage.error('复制失败，请手动选择')
  }
}

// ------------------------------------------------------------------
// 文件夹导入（前端粗筛 + 分批发送 + 结果合并记账）
// ------------------------------------------------------------------

/** 与后端 SKIP_DIRS 对应的粗筛；规则以后端为准，这里只为少传无用数据 */
const NOISE_DIRS = [
  '.git', 'node_modules', 'bower_components', 'vendor', 'third_party', 'target', 'build',
  'dist', 'out', 'coverage', '.idea', '.vscode', '.gradle', 'venv', '.venv', 'env',
  '__pycache__', 'site-packages', '.next', '.nuxt', '.cache', 'logs', 'tmp',
]
const NOISE_EXT = [
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'svg', 'woff', 'woff2', 'ttf', 'otf',
  'zip', 'gz', 'tar', 'rar', '7z', 'jar', 'war', 'class', 'exe', 'dll', 'so', 'dylib',
  'pdf', 'docx', 'xlsx', 'pptx', 'mp3', 'mp4', 'mov', 'bin', 'dat', 'db', 'sqlite', 'pyc', 'map',
]
/** 与后端 MAX_FILE_BYTES 保持一致（2MB） */
const MAX_CLIENT_FILE = 2 * 1024 * 1024
/** 分批：大仓库一次几十 MB 的 JSON 请求不现实，按 150 个文件 / 6MB 一批发，最后合并总账 */
const BATCH_FILES = 150
const BATCH_BYTES = 6 * 1024 * 1024

function pickFolder() {
  folderInput.value?.click()
}

function extOf(path) {
  const base = path.slice(path.lastIndexOf('/') + 1)
  const dot = base.lastIndexOf('.')
  return dot < 0 ? '' : base.slice(dot + 1).toLowerCase()
}

function prefilter(list) {
  const keep = []
  let dropped = 0
  for (const f of list) {
    const rel = (f.webkitRelativePath || f.name).replace(/\\/g, '/')
    const dirs = rel.split('/').slice(0, -1)
    if (dirs.some((d) => NOISE_DIRS.includes(d))) {
      dropped++
      continue
    }
    if (NOISE_EXT.includes(extOf(rel))) {
      dropped++
      continue
    }
    if (f.size > MAX_CLIENT_FILE) {
      dropped++
      continue
    }
    keep.push(f)
  }
  return { keep, dropped }
}

function mergeSummary(acc, r) {
  acc.created += r.created || 0
  acc.updated += r.updated || 0
  acc.skipped += r.skipped || 0
  acc.bytes += r.bytes || 0
  for (const l of r.langs || []) {
    acc.langs[l.lang] = (acc.langs[l.lang] || 0) + l.count
  }
  for (const [k, v] of Object.entries(r.skipReasons || {})) {
    acc.skipReasons[k] = (acc.skipReasons[k] || 0) + v
  }
  for (const s of r.skipSamples || []) {
    if (acc.skipSamples.length < 12) {
      acc.skipSamples.push(s)
    }
  }
}

async function onFolderPicked(e) {
  const picked = Array.from(e.target.files || [])
  e.target.value = ''
  if (!picked.length) {
    return
  }
  const { keep, dropped } = prefilter(picked)
  if (!keep.length) {
    ElMessage.warning(`选中的 ${picked.length} 个文件都被预筛掉了（依赖目录/二进制/超大文件）`)
    return
  }
  const first = (keep[0].webkitRelativePath || keep[0].name).replace(/\\/g, '/')
  const projectName = first.includes('/') ? first.split('/')[0] : '导入的代码'
  importing.value = true
  importProgress.value = `读取 ${keep.length} 个文件…`
  try {
    const files = await Promise.all(keep.map(async (f) => ({
      path: (f.webkitRelativePath || f.name).replace(/\\/g, '/'),
      text: await f.text(),
    })))
    const acc = { created: 0, updated: 0, skipped: 0, bytes: 0, langs: {}, skipReasons: {}, skipSamples: [] }
    let batch = []
    let batchBytes = 0
    let done = 0
    for (const f of files) {
      const size = f.text.length
      if (batch.length && (batch.length >= BATCH_FILES || batchBytes + size > BATCH_BYTES)) {
        importProgress.value = `导入 ${done}/${files.length}…`
        mergeSummary(acc, await codeApi.importFolder({ projectName, files: batch }))
        done += batch.length
        batch = []
        batchBytes = 0
      }
      batch.push(f)
      batchBytes += size
    }
    if (batch.length) {
      mergeSummary(acc, await codeApi.importFolder({ projectName, files: batch }))
      done += batch.length
    }
    const langList = Object.entries(acc.langs).sort((a, b) => b[1] - a[1])
      .map(([k, v]) => `${k}×${v}`).join('、') || '（无）'
    const reasons = Object.entries(acc.skipReasons).map(([k, v]) => `${k}：${v}`).join('<br>') || '（无）'
    const samples = acc.skipSamples.slice(0, 6).map((s) => `· ${s}`).join('<br>')
    await ElMessageBox.alert(
      `识别并入库：<b>新增 ${acc.created}</b>、更新 ${acc.updated}`
      + `<br>跳过 ${acc.skipped}（预筛另丢 ${dropped}）· 共 ${(acc.bytes / 1024 / 1024).toFixed(2)} MB`
      + `<br><br>识别到的语言：${langList}`
      + `<br><br><b>跳过原因</b><br>${reasons}`
      + (samples ? `<br><br><b>样例</b><br><span style="color:#6b7280">${samples}</span>` : '')
      + '<br><br><span style="color:#6b7280">跳过规则：依赖目录（node_modules/target/dist…）、'
      + '二进制资源、锁文件、压缩产物、空文件、单个超过 2MB 的文件；'
      + '扩展名不认识但内容像代码的会标成 guessed 入库。</span>',
      `导入「${projectName}」完成`,
      { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' },
    )
    await loadAll()
    // 导完直接进入该项目，省一次点击
    const created = repos.value.find((r) => r.name === projectName)
    if (created) {
      await enterRepo(created)
    } else {
      backToProjects()
    }
  } catch (err) {
    /* 拦截器已提示 */
  } finally {
    importing.value = false
    importProgress.value = ''
  }
}

const KIND_LABEL = {
  class: '类', interface: '接口', enum: '枚举', record: '记录',
  function: '函数', method: '方法', const: '常量', file: '文件',
}

onMounted(async () => {
  await loadAll()
})
</script>

<template>
  <div class="page code-page">
    <div class="head">
      <div>
        <h2 class="page-h2">{{ pageTitle }}</h2>
        <p class="head-sub">
          <template v-if="view === 'projects'">
            代码片段按项目分组存放（点项目进去看）。与知识库分开：代码混进知识检索会把笔记挤下去。
            共 <b>{{ stats.snippets }}</b> 段 / <b>{{ stats.symbols }}</b> 个符号 / <b>{{ stats.repos }}</b> 个项目
          </template>
          <template v-else>
            <template v-if="searching">
              全库检索结果（不限项目）
            </template>
            <template v-else-if="currentRepo">
              <a v-if="currentRepo.url" :href="currentRepo.url" target="_blank" rel="noreferrer" class="repo-link">仓库</a>
              <a v-if="currentRepo.demoUrl" :href="currentRepo.demoUrl" target="_blank" rel="noreferrer" class="repo-link">演示</a>
              <span v-if="currentRepo.license" class="hint"> · {{ currentRepo.license }}</span>
              <span class="hint"> · {{ rows.length }} 段</span>
            </template>
          </template>
        </p>
      </div>
      <div class="head-actions">
        <el-button size="small" :loading="importing" @click="pickFolder">上传文件夹</el-button>
        <el-button size="small" type="primary" @click="openForm(null)">＋ 新增片段</el-button>
        <input ref="folderInput" type="file" webkitdirectory multiple class="folder-input" @change="onFolderPicked" />
      </div>
    </div>

    <p v-if="importing" class="hint importing">{{ importProgress }}</p>

    <!-- ============ 项目层：项目直接铺在页面上 ============ -->
    <template v-if="view === 'projects'">
      <div class="section-head">
        <span class="section-title">项目</span>
        <span class="hint">文件夹导入会自动建项目；项目里可以挂仓库与演示站链接</span>
        <el-button size="small" text @click="openRepoForm(null)">＋ 新建项目</el-button>
      </div>
      <div class="repo-grid">
        <div v-for="r in repos" :key="r.id" class="repo-card" @click="enterRepo(r)">
          <div class="repo-card-top">
            <span class="repo-card-name">{{ r.name }}</span>
            <span class="tag">{{ r.snippets }} 段</span>
            <span v-if="r.license" class="tag">{{ r.license }}</span>
          </div>
          <p class="repo-card-note">{{ r.note || '（无说明）' }}</p>
          <div class="repo-card-foot">
            <a v-if="r.url" :href="r.url" target="_blank" rel="noreferrer" class="repo-link" @click.stop>仓库</a>
            <a v-if="r.demoUrl" :href="r.demoUrl" target="_blank" rel="noreferrer" class="repo-link" @click.stop>演示</a>
            <span class="spacer" />
            <el-button size="small" text @click.stop="openRepoForm(r)">编辑</el-button>
            <el-button size="small" text @click.stop="removeRepo(r)">删除</el-button>
          </div>
        </div>
        <!-- 未归类：不属于任何项目的片段 -->
        <div v-if="orphanCount" class="repo-card" @click="enterRepo({ id: null, name: '未归类' })">
          <div class="repo-card-top">
            <span class="repo-card-name">未归类</span>
            <span class="tag">{{ orphanCount }} 段</span>
          </div>
          <p class="repo-card-note">没有挂到项目上的片段</p>
        </div>
        <p v-if="!repos.length && !orphanCount" class="hint empty-card">
          还没有项目 —— 点右上「上传文件夹」导入一个代码目录，或在下面「＋ 新增片段」手动加
        </p>
      </div>

      <!-- 项目层的全库检索 -->
      <div class="search-bar">
        <el-input v-model="keyword" placeholder="全库检索：符号名或关键词（回车）" clearable
                  style="flex: 1" @keydown.enter="runSearch" />
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="symbol">符号</el-radio-button>
          <el-radio-button value="keyword">关键词</el-radio-button>
        </el-radio-group>
        <el-select v-model="langFilter" placeholder="语言" clearable size="small" style="width: 110px">
          <el-option v-for="l in LANGS" :key="l" :label="l" :value="l" />
        </el-select>
        <el-button size="small" type="primary" :loading="loading" @click="runSearch">检索</el-button>
      </div>
      <p class="hint mode-hint">{{ modeHint }}</p>
    </template>

    <!-- ============ 片段层：某个项目内 / 搜索结果 ============ -->
    <template v-else>
      <div class="crumb">
        <el-button size="small" text @click="backToProjects">← 返回项目</el-button>
        <span class="crumb-path">{{ pageTitle }}</span>
        <span class="spacer" />
        <span v-if="rows.length >= LIST_LIMIT" class="hint">仅显示前 {{ LIST_LIMIT }} 段，可用检索缩小范围</span>
      </div>

      <div class="search-bar">
        <el-input v-model="keyword"
                  :placeholder="searching ? '继续在全库检索…' : `在「${pageTitle}」内检索：符号名或关键词（回车）`"
                  clearable style="flex: 1" @keydown.enter="runSearch" />
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="symbol">符号</el-radio-button>
          <el-radio-button value="keyword">关键词</el-radio-button>
        </el-radio-group>
        <el-select v-model="langFilter" placeholder="语言" clearable size="small" style="width: 110px">
          <el-option v-for="l in LANGS" :key="l" :label="l" :value="l" />
        </el-select>
        <el-button size="small" type="primary" :loading="loading" @click="runSearch">检索</el-button>
        <el-button size="small" @click="resetFilters">重置</el-button>
      </div>
      <p class="hint mode-hint">{{ modeHint }}</p>

      <div class="code-body">
        <div v-loading="loading" class="result-list">
          <div v-for="r in rows" :key="r.id" class="result-card" @click="openDetail(r)">
            <div class="card-top">
              <span class="card-title">{{ r.title }}</span>
              <span v-if="r.lang" class="tag lang">{{ r.lang }}</span>
              <span v-if="r.hitSymbol" class="tag hit" :title="'命中符号，定义在第 ' + r.hitLine + ' 行'">
                {{ KIND_LABEL[r.hitKind] || r.hitKind }} {{ r.hitSymbol }} · L{{ r.hitLine }}
              </span>
              <span v-else-if="r.matchBy === 'keyword'" class="tag">关键词命中</span>
              <span v-if="searching && r.repoName" class="tag">{{ r.repoName }}</span>
            </div>
            <p class="card-explain">{{ r.explainBrief || '（未写说明）' }}</p>
            <div class="card-foot">
              <span v-if="r.filePath" class="hint">{{ r.filePath }}</span>
              <span class="hint">{{ r.lineCount }} 行</span>
              <span class="spacer" />
              <el-button size="small" text @click.stop="openForm(r)">编辑</el-button>
              <el-button size="small" text @click="removeSnippet(r, $event)">删除</el-button>
            </div>
          </div>
          <p v-if="!rows.length && !loading" class="hint empty">
            没有内容 —— 换个关键词；或点右上「上传文件夹」导入代码
          </p>
        </div>

        <aside v-if="detail" v-loading="detailLoading" class="detail">
          <div class="detail-head">
            <b>{{ detail.title }}</b>
            <el-button size="small" text @click="detail = null">收起</el-button>
          </div>
          <p v-if="detail.explainText" class="detail-explain">{{ detail.explainText }}</p>
          <div class="detail-links">
            <a v-if="detail.sourceUrl" :href="detail.sourceUrl" target="_blank" rel="noreferrer">出处</a>
            <a v-if="detail.repoUrl" :href="detail.repoUrl" target="_blank" rel="noreferrer">仓库</a>
            <a v-if="detail.demoUrl" :href="detail.demoUrl" target="_blank" rel="noreferrer">演示</a>
            <span v-if="detail.filePath" class="hint">{{ detail.filePath }}</span>
          </div>
          <div v-if="detail.symbols?.length" class="symbol-list">
            <span class="hint">符号索引：</span>
            <span v-for="s in detail.symbols" :key="s.kind + s.name + s.line" class="tag small">
              {{ KIND_LABEL[s.kind] || s.kind }} {{ s.name }} · L{{ s.line }}
            </span>
          </div>
          <div class="code-head">
            <span class="hint">{{ detail.lang }} · {{ detail.lineCount }} 行</span>
            <el-button size="small" text @click="copyCode(detail.code)">复制代码</el-button>
          </div>
          <pre class="code-block"><code>{{ detail.code }}</code></pre>
        </aside>
      </div>
    </template>

    <!-- 片段编辑 -->
    <el-dialog :model-value="!!form" :title="form?.id ? '编辑代码片段' : '新增代码片段'" width="820px"
               top="6vh" destroy-on-close @close="form = null">
      <div v-if="form" class="form-grid">
        <label class="fl">标题</label>
        <el-input v-model="form.title" placeholder="这段代码干什么（如：按标题切块并在围栏内跳过）" />
        <label class="fl">语言</label>
        <el-select v-model="form.lang" style="width: 160px">
          <el-option v-for="l in LANGS" :key="l" :label="l" :value="l" />
        </el-select>
        <label class="fl">项目</label>
        <el-select v-model="form.repoId" placeholder="（可不选）" clearable style="width: 260px">
          <el-option v-for="r in repos" :key="r.id" :label="r.name" :value="r.id" />
        </el-select>
        <label class="fl">原文件</label>
        <el-input v-model="form.filePath" placeholder="路径（用于定位，如 backend/.../TextChunker.java）" />
        <label class="fl">出处链接</label>
        <el-input v-model="form.sourceUrl" placeholder="仓库文件页 / 文档地址（可空）" />
        <label class="fl">说明</label>
        <el-input v-model="form.explainText" type="textarea" :rows="3"
                  placeholder="为什么这么写 / 怎么用 / 踩过什么坑 —— 这段说明才是检索最有价值的部分" />
        <label class="fl">代码</label>
        <el-input v-model="form.code" type="textarea" :rows="14" class="code-input"
                  placeholder="粘贴代码（保存时会自动抽取类/函数/方法名并建符号索引）" />
      </div>
      <template #footer>
        <el-button @click="form = null">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveForm">保存并建索引</el-button>
      </template>
    </el-dialog>

    <!-- 项目编辑 -->
    <el-dialog :model-value="!!repoForm" :title="repoForm?.id ? '编辑项目' : '新增项目'" width="620px"
               @close="repoForm = null">
      <div v-if="repoForm" class="form-grid">
        <label class="fl">名称</label>
        <el-input v-model="repoForm.name" placeholder="如 paper-x-implementation" />
        <label class="fl">仓库地址</label>
        <el-input v-model="repoForm.url" placeholder="https://github.com/..." />
        <label class="fl">演示站</label>
        <el-input v-model="repoForm.demoUrl" placeholder="https://... （JS 渲染的演示站抓不到内容，存链接即可）" />
        <label class="fl">许可证</label>
        <el-input v-model="repoForm.license" placeholder="引用别人的代码必须留痕，如 MIT / Apache-2.0" />
        <label class="fl">说明</label>
        <el-input v-model="repoForm.note" placeholder="一句话说明" />
      </div>
      <template #footer>
        <el-button @click="repoForm = null">取消</el-button>
        <el-button type="primary" @click="saveRepoForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page.code-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
  align-items: center;
}
.folder-input {
  display: none;
}
.importing {
  margin: -4px 0 0;
  color: var(--app-brand-deep);
}
/* 项目网格：像文件夹一样铺开 */
.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-1);
}
.repo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
}
.repo-card {
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  padding: 12px;
  background: var(--app-card);
  cursor: pointer;
  transition: border-color var(--dur-fast) ease;
}
.repo-card:hover {
  border-color: color-mix(in srgb, var(--app-brand) 45%, transparent);
}
.repo-card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.repo-card-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--app-text-1);
}
.repo-card-note {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--app-text-3);
  line-height: 1.6;
  min-height: 19px;
}
.repo-card-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.empty-card {
  grid-column: 1 / -1;
  padding: 14px 0;
}
.repo-link {
  color: var(--app-brand-deep);
  font-size: 12px;
  text-decoration: none;
}
.repo-link:hover {
  text-decoration: underline;
}
.search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.mode-hint {
  margin: -4px 0 0;
}
.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
}
.crumb-path {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-1);
}
.code-body {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
}
.code-body:has(.detail) {
  grid-template-columns: minmax(0, 1fr) minmax(360px, 46%);
}
.result-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 120px;
}
.result-card {
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  padding: 10px 12px;
  background: var(--app-card);
  cursor: pointer;
  transition: border-color var(--dur-fast) ease;
}
.result-card:hover {
  border-color: color-mix(in srgb, var(--app-brand) 40%, transparent);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.card-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--app-text-1);
}
.tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 5px;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  color: var(--app-text-2);
}
.tag.small {
  font-size: 10.5px;
}
.tag.lang {
  color: var(--app-brand-deep);
  border-color: color-mix(in srgb, var(--app-brand) 40%, transparent);
}
.tag.hit {
  background: var(--app-brand-soft);
  color: var(--app-brand-deep);
  border-color: color-mix(in srgb, var(--app-brand) 45%, transparent);
}
.card-explain {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--app-text-2);
  line-height: 1.6;
}
.card-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.spacer {
  flex: 1 1 auto;
}
.empty {
  padding: 16px 0;
}
.detail {
  border: 1px solid var(--app-border-weak);
  border-radius: var(--radius);
  background: var(--app-card);
  padding: 12px;
  min-height: 0;
  overflow-y: auto;
}
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.detail-explain {
  margin: 6px 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--app-text-2);
}
.detail-links {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
}
.detail-links a {
  color: var(--app-brand-deep);
}
.symbol-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  align-items: center;
  margin: 8px 0;
}
.code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
}
.code-block {
  margin: 6px 0 0;
  padding: 10px;
  background: var(--app-bg);
  border: 1px solid var(--app-border-weak);
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.55;
  overflow-x: auto;
  white-space: pre;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--app-text-1);
}
.form-grid {
  display: grid;
  grid-template-columns: 74px minmax(0, 1fr);
  align-items: center;
  gap: 10px 12px;
}
.form-grid .fl {
  font-size: 12.5px;
  color: var(--app-text-2);
  text-align: right;
}
.code-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
