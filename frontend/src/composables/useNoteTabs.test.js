import test from 'node:test'
import assert from 'node:assert/strict'
import { createNoteTabsManager, isNoteEditorRoute, newNoteDraftQuery, noteTabRoute } from './useNoteTabs.js'

const route = (id, query = {}, hash = '') => ({ path: `/notes/${id}`, params: id === 'new' ? {} : { id: String(id) }, query, hash })
const memoryStorage = initial => {
  let value = initial || null
  return { getItem: () => value, setItem: (_, next) => { value = next }, get value() { return value } }
}

test('opening the same note at a new chapter changes its route without duplicating its editor key', () => {
  const manager = createNoteTabsManager()
  const first = manager.openNoteRoute(route(7, { folderId: '2' }))
  manager.updateNoteTab(first.key, { title: '检索笔记', dirty: true })
  const second = manager.openNoteRoute(route(7, { section: '实验' }, '#section-3'))
  assert.equal(first.key, second.key)
  assert.equal(manager.noteTabs.value.length, 1)
  assert.equal(second.dirty, true)
  assert.deepEqual(noteTabRoute(second), { path: '/notes/7', query: { section: '实验' }, hash: '#section-3' })
  for (const path of ['/notes', '/notes/abc', '/files', '/notes/0']) assert.equal(isNoteEditorRoute({ path }), false)
})

test('separate drafts can stay open and saving promotes their original instance key', () => {
  const manager = createNoteTabsManager()
  const a = manager.openNoteRoute(route('new', { draft: 'a', folderId: '4' }))
  manager.openNoteRoute(route('new', { draft: 'b' }))
  manager.openNoteRoute(route(18))
  manager.updateNoteTab(a.key, { title: '新实验', dirty: true })
  const promoted = manager.promoteNoteTab(a.key, 18)
  assert.equal(promoted.key, 'draft:a')
  assert.equal(promoted.id, 18)
  assert.equal(promoted.dirty, false)
  assert.deepEqual(promoted.query, { folderId: '4' })
  assert.equal(manager.noteTabs.value.length, 2)
  assert.equal(manager.findRouteTab(route(18)).key, 'draft:a')
  assert.equal(manager.promoteNoteTab('missing', 18), null)
  assert.equal(manager.promoteNoteTab(a.key, -1), null)
})

test('session metadata excludes draft bodies, dirty state and arbitrary route payloads', () => {
  const storage = memoryStorage()
  const manager = createNoteTabsManager(storage)
  const tab = manager.openNoteRoute(route(7, { folderId: '3', content: 'private body', token: 'secret' }, '#section-a'))
  manager.updateNoteTab(tab.key, { title: '笔记 A', dirty: true, content: 'private body' })
  manager.openNoteRoute(route('new', { draft: 'unsaved' }))
  assert.deepEqual(JSON.parse(storage.value), [{ id: 7, title: '笔记 A', query: { folderId: '3' }, hash: '#section-a' }])
  const restored = createNoteTabsManager(storage)
  assert.equal(restored.noteTabs.value.length, 1)
  assert.equal(restored.noteTabs.value[0].dirty, false)
  assert.equal(restored.noteTabs.value[0].title, '笔记 A')
})

test('closing returns an adjacent tab and keeps other titles, dirty state and navigation intact', () => {
  const manager = createNoteTabsManager()
  const a = manager.openNoteRoute(route(1)), b = manager.openNoteRoute(route(2)), c = manager.openNoteRoute(route(3))
  manager.updateNoteTab(c.key, { dirty: true })
  assert.equal(manager.closeNoteTab(b.key).key, c.key)
  assert.equal(c.dirty, true)
  assert.equal(manager.closeNoteTab(c.key).key, a.key)
  assert.equal(manager.closeNoteTab(a.key), null)
  assert.equal(manager.closeNoteTab('missing'), null)
})

test('malformed or blocked browser storage cannot stop editing; invalid restored IDs are ignored', () => {
  const blocked = createNoteTabsManager({ getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') } })
  assert.equal(blocked.openNoteRoute(route(8)).id, 8)
  const malformed = createNoteTabsManager(memoryStorage('{broken'))
  assert.equal(malformed.noteTabs.value.length, 0)
  const restored = createNoteTabsManager(memoryStorage(JSON.stringify([{ id: 2 }, { id: 2 }, { id: -3 }, { id: 'bad' }, null])))
  assert.deepEqual(restored.noteTabs.value.map(tab => tab.id), [2])
})

test('new-tab routes always receive independent draft identities and retain only the folder context', () => {
  const a = newNoteDraftQuery({ folderId: '8', section: 'old', draft: 'old' })
  const b = newNoteDraftQuery({ folderId: '8' })
  assert.notEqual(a.draft, b.draft)
  assert.equal(a.folderId, '8')
  assert.equal(a.section, undefined)
})

test('a legacy editor=md note retains its renderer when switching tabs or restoring the session', () => {
  const storage = memoryStorage()
  const manager = createNoteTabsManager(storage)
  const tab = manager.openNoteRoute(route(9, { editor: 'md' }))
  assert.equal(noteTabRoute(tab).query.editor, 'md')
  assert.equal(createNoteTabsManager(storage).noteTabs.value[0].query.editor, 'md')
})
