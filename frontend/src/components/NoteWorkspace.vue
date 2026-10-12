<script setup>
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NoteTabs from './NoteTabs.vue'
import {
  noteTabs, openNoteRoute, findNoteRouteTab, updateNoteTab, closeNoteTab,
  isNoteEditorRoute, newNoteDraftQuery, noteTabRoute,
} from '../composables/useNoteTabs.js'

const NoteEdit = defineAsyncComponent(() => import('../views/NoteEdit.vue'))
const route = useRoute()
const router = useRouter()
const closingKey = ref('')
const visitedKeys = ref(new Set())
const editors = new Map()
const isEditor = computed(() => isNoteEditorRoute(route))
const activeTab = computed(() => isEditor.value ? findNoteRouteTab(route) : null)
const activeKey = computed(() => activeTab.value?.key || '')
const mountedTabs = computed(() => noteTabs.value.filter(tab => visitedKeys.value.has(tab.key)))

watch(() => route.fullPath, () => {
  const tab = openNoteRoute(route)
  if (tab) visitedKeys.value = new Set([...visitedKeys.value, tab.key])
}, { immediate: true })

function setEditor(key, editor) {
  if (editor) editors.set(key, editor)
  else editors.delete(key)
}

function selectTab(key) {
  const tab = noteTabs.value.find(tab => tab.key === key)
  if (tab) return router.push(noteTabRoute(tab))
}

function listRoute(tab = activeTab.value) {
  const folderId = tab?.query?.folderId
  return { path: '/notes', query: folderId != null ? { folderId } : {} }
}

function newTab() {
  return router.push({ path: '/notes/new', query: newNoteDraftQuery(route.query), hash: '' })
}

async function closeTab(key) {
  if (closingKey.value) return
  const tab = noteTabs.value.find(tab => tab.key === key)
  if (!tab) return
  closingKey.value = key
  try {
    const editor = editors.get(key)
    if (editor?.prepareTabClose && !(await editor.prepareTabClose())) return
    // The user may select another tab while the unsaved-change dialog is open.
    // Only navigate if the closing tab is still the active one now.
    if (activeKey.value === key) {
      const index = noteTabs.value.findIndex(item => item.key === key)
      const next = noteTabs.value[index + 1] || noteTabs.value[index - 1]
      const failed = await router.replace(next ? noteTabRoute(next) : listRoute(tab))
      if (failed) return
    }
    closeNoteTab(key)
    visitedKeys.value = new Set([...visitedKeys.value].filter(item => item !== key))
  } finally { closingKey.value = '' }
}
</script>

<template>
  <section class="note-workspace" :class="{ listing: !isEditor }">
    <NoteTabs :tabs="noteTabs" :active-key="activeKey" :closing-key="closingKey" @select="selectTab" @close="closeTab" @new="newTab" @list="router.push(listRoute())" />
    <div class="note-workspace-editors" v-show="isEditor">
      <div v-for="tab in mountedTabs" :key="tab.key" v-show="activeKey === tab.key" :id="`note-panel-${encodeURIComponent(tab.key)}`" role="tabpanel" :aria-labelledby="`note-tab-${encodeURIComponent(tab.key)}`" class="note-editor-panel">
        <NoteEdit
          :ref="editor => setEditor(tab.key, editor)"
          :note-id="tab.id"
          :note-query="tab.query"
          :tab-key="tab.key"
          :active="activeKey === tab.key"
          @tab-state="state => updateNoteTab(tab.key, state)"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.note-workspace { display: flex; flex-direction: column; height: 100%; min-width: 0; min-height: 0; }
.note-workspace.listing { height: 52px; }
.note-workspace-editors, .note-editor-panel { flex: 1; min-height: 0; height: 100%; }
.note-workspace-editors { overflow: auto; }
@media (max-width: 680px) { .note-workspace.listing { height: 48px; } }
</style>
