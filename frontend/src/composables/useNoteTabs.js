import { ref } from 'vue'

const STORAGE_KEY = 'learnhub-note-tabs-v1'

function normalizeId(value) {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

function copyQuery(query = {}) {
  const result = {}
  for (const [key, value] of Object.entries(query)) {
    // Only route metadata is stored. Never retain an arbitrary pasted payload.
    if (!['folderId', 'draft', 'section', 'heading', 'editor'].includes(key)) continue
    if (value === null || typeof value === 'string' || typeof value === 'number') result[key] = value
    else if (Array.isArray(value)) result[key] = value.filter(v => v === null || typeof v === 'string').slice(0, 4)
  }
  return result
}

export function isNoteEditorRoute(route) {
  return /^\/notes\/(?:new|[1-9]\d*)\/?$/.test(route?.path || '')
}

export function noteRouteIdentity(route) {
  if (!isNoteEditorRoute(route)) return null
  if (/\/new\/?$/.test(route.path)) return `draft:${String(route.query?.draft || 'default')}`
  const id = normalizeId(route.params?.id || route.path.split('/')[2])
  return id ? `note:${id}` : null
}

export function noteTabRoute(tab) {
  const query = copyQuery(tab?.query)
  if (tab?.id) delete query.draft
  return { path: tab?.id ? `/notes/${tab.id}` : '/notes/new', query, hash: tab?.hash || '' }
}

export function newNoteDraftQuery(query = {}) {
  const draft = globalThis.crypto?.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  return { ...(query.folderId != null ? { folderId: query.folderId } : {}), draft }
}

/** A session stores saved-note tab metadata, never editor bodies or unsaved drafts. */
export function createNoteTabsManager(storage = null) {
  const tabs = ref([])
  try {
    const saved = JSON.parse(storage?.getItem(STORAGE_KEY) || '[]')
    const seen = new Set()
    if (Array.isArray(saved)) tabs.value = saved.slice(0, 40).flatMap(item => {
      const id = normalizeId(item?.id)
      if (!id || seen.has(id)) return []
      seen.add(id)
      return [{ key: `note:${id}`, id, title: String(item.title || '笔记').slice(0, 200), dirty: false, query: copyQuery(item.query), hash: typeof item.hash === 'string' ? item.hash : '' }]
    })
  } catch { /* Private browsing and stale metadata must not prevent editing. */ }

  function persist() {
    try {
      storage?.setItem(STORAGE_KEY, JSON.stringify(tabs.value.filter(tab => tab.id).map(({ id, title, query, hash }) => ({ id, title, query, hash }))))
    } catch { /* Editor state continues to work without browser storage. */ }
  }

  function findRouteTab(route) {
    const identity = noteRouteIdentity(route)
    if (!identity) return null
    return tabs.value.find(tab => tab.id ? identity === `note:${tab.id}` : identity === tab.key) || null
  }

  function openNoteRoute(route) {
    const identity = noteRouteIdentity(route)
    if (!identity) return null
    const existing = findRouteTab(route)
    if (existing) {
      existing.query = copyQuery(route.query)
      existing.hash = route.hash || ''
      persist()
      return existing
    }
    const id = identity.startsWith('note:') ? normalizeId(identity.slice(5)) : null
    const tab = { key: identity, id, title: id ? '载入笔记…' : '新建笔记', dirty: false, query: copyQuery(route.query), hash: route.hash || '' }
    tabs.value.push(tab)
    persist()
    return tabs.value[tabs.value.length - 1]
  }

  function updateNoteTab(key, state = {}) {
    const tab = tabs.value.find(item => item.key === key)
    if (!tab) return
    let changed = false
    if (typeof state.title === 'string') {
      const title = state.title.trim().slice(0, 200) || (tab.id ? '未命名笔记' : '新建笔记')
      if (tab.title !== title) { tab.title = title; changed = true }
    }
    if (typeof state.dirty === 'boolean' && tab.dirty !== state.dirty) {
      tab.dirty = state.dirty
      changed = true
    }
    if (changed) persist()
  }

  function promoteNoteTab(key, value, query) {
    const id = normalizeId(value)
    const tab = tabs.value.find(item => item.key === key)
    if (!tab || !id) return null
    // Preserve the editor instance key when a draft receives its database ID.
    tabs.value = tabs.value.filter(item => item.key === key || item.id !== id)
    tab.id = id
    tab.dirty = false
    tab.query = copyQuery(query || tab.query)
    delete tab.query.draft
    persist()
    return tab
  }

  function closeNoteTab(key) {
    const index = tabs.value.findIndex(tab => tab.key === key)
    if (index < 0) return null
    tabs.value.splice(index, 1)
    persist()
    return tabs.value[Math.min(index, tabs.value.length - 1)] || null
  }

  return { noteTabs: tabs, openNoteRoute, findRouteTab, updateNoteTab, promoteNoteTab, closeNoteTab }
}

let storage = null
try { storage = globalThis.sessionStorage || null } catch { /* Storage may be disabled. */ }
const manager = createNoteTabsManager(storage)
export const noteTabs = manager.noteTabs
export const openNoteRoute = manager.openNoteRoute
export const findNoteRouteTab = manager.findRouteTab
export const updateNoteTab = manager.updateNoteTab
export const promoteNoteTab = manager.promoteNoteTab
export const closeNoteTab = manager.closeNoteTab
export const useNoteTabs = () => manager
