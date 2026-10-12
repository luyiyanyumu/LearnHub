import test from 'node:test'
import assert from 'node:assert/strict'
import {
  folderExplorerBreadcrumbs,
  folderExplorerQuery,
  folderExplorerRows,
  folderExplorerScope,
  isExplorerFolder,
  parentFolderScope,
} from './folderExplorer.js'

const folders = [
  { id: 12, parentId: 11, name: 'Papers', itemCount: 9 },
  { id: 11, parentId: 0, name: 'Research', itemCount: 2 },
  { id: 13, parentId: 12, name: 'To read', itemCount: 4 },
  { id: 21, parentId: 0, name: 'Courses', itemCount: 0 },
]

test('root and folder pages show only direct child folders before their supplied items', () => {
  const items = [{ id: 11, title: 'A synthetic note', folderId: null }]
  const root = folderExplorerRows(folders, items, { scope: 0 })
  assert.deepEqual(root.map(row => row.__explorerKey), ['folder:11', 'folder:21', 'note:11'])
  assert.equal(root[0].childrenCount, 1)
  assert.equal(root[0].itemCount, 2)
  assert.equal(root[1].childrenCount, 0)
  assert.equal(root.every(row => !Object.hasOwn(row, 'children')), true)
  assert.equal(isExplorerFolder(root[0]), true)
  assert.equal(isExplorerFolder(root[2]), false)
  assert.notEqual(root[2], items[0])
  assert.deepEqual(root[2], { ...items[0], __folder: false, __explorerKey: 'note:11' })

  const nested = folderExplorerRows(folders, [{ id: 50 }], { scope: '11', kind: 'file' })
  assert.deepEqual(nested.map(row => row.__explorerKey), ['folder:12', 'file:50'])
  assert.equal(nested[0].itemCount, 9)
  assert.equal(nested[0].childrenCount, 1)
  assert.equal(nested.every(row => !Object.hasOwn(row, 'children')), true)
  assert.deepEqual(folderExplorerRows(folders, [], { scope: 13 }), [])
})

test('aggregate results have no folder rows and supplied pagination does not alter directory counts', () => {
  const items = [{ id: 90, folderId: 12 }, { id: 91, folderId: 21 }]
  assert.deepEqual(folderExplorerRows(folders, items, { scope: null }).map(row => row.__explorerKey), ['note:90', 'note:91'])
  assert.equal(folderExplorerRows(folders, [], { scope: 11 })[0].itemCount, 9)
  assert.equal(folderExplorerRows(folders, items.slice(0, 1), { scope: 11 })[0].itemCount, 9)
  assert.equal(folderExplorerRows(folders, items)[0].__folder, false)
})

test('keyword search filters direct folder names case-insensitively without dropping externally matched contents', () => {
  const items = [{ id: 15, title: 'A name that does not contain the requested keyword', tags: ['matched remotely'] }]
  const rows = folderExplorerRows(folders, items, { scope: 0, keyword: '  RESEARCH  ' })
  assert.deepEqual(rows.map(row => row.__explorerKey), ['folder:11', 'note:15'])
  assert.equal(rows[1].tags, items[0].tags)
  assert.deepEqual(folderExplorerRows(folders, items, { scope: 0, keyword: 'missing' }).map(row => row.__explorerKey), ['note:15'])
})

test('breadcrumbs and up navigation use actual hierarchy, never names split on slash', () => {
  assert.deepEqual(folderExplorerBreadcrumbs(folders, '13'), [
    { id: 11, name: 'Research' }, { id: 12, name: 'Papers' }, { id: 13, name: 'To read' },
  ])
  assert.equal(parentFolderScope(folders, 13), 12)
  assert.equal(parentFolderScope(folders, 12), 11)
  for (const scope of [null, 0, 11, 999, 'invalid']) assert.equal(parentFolderScope(folders, scope), 0)
  for (const scope of [null, 0, 999, 'invalid']) assert.deepEqual(folderExplorerBreadcrumbs(folders, scope), [])
  assert.deepEqual(folderExplorerBreadcrumbs([{ id: 1, name: 'Name / with slash' }], 1), [{ id: 1, name: 'Name / with slash' }])
})

test('malformed parents, cycles and duplicates remain finite and reachable with collision-free keys', () => {
  const damaged = [
    { id: 1, parentId: 2, name: 'A' }, { id: 2, parentId: 1, name: 'B' },
    { id: 3, parentId: 999, name: 'Orphan', itemCount: '4' },
    { id: '4', parentId: '3', name: 'Child', itemCount: '-1' },
    { id: 3, name: 'Duplicate' }, null, { id: -1 }, { id: 0 },
  ]
  const rows = folderExplorerRows(damaged, [{ id: 1 }, { id: 1 }, { id: '1:2' }, {}, {}, null, false, []], { scope: 0 })
  assert.equal(new Set(rows.map(row => row.__explorerKey)).size, rows.length)
  assert.equal(rows.filter(row => row.__folder).length, 2)
  assert.equal(rows.find(row => row.id === 3 && row.__folder).itemCount, 4)
  assert.deepEqual(folderExplorerBreadcrumbs(damaged, 4), [{ id: 3, name: 'Orphan' }, { id: 4, name: 'Child' }])
  assert.deepEqual(folderExplorerBreadcrumbs(damaged, 2), [{ id: 1, name: 'A' }, { id: 2, name: 'B' }])
  assert.equal(parentFolderScope(damaged, 1), 0)
  assert.equal(parentFolderScope(damaged, 4), 3)
  assert.equal(folderExplorerRows(damaged, [], { scope: 3 })[0].itemCount, 0)
  assert.deepEqual(folderExplorerRows(null, null, { scope: 0 }), [])
  assert.equal(isExplorerFolder(null), false)
})

test('helpers never modify API objects, original IDs, metadata or route query', () => {
  const originalFolders = structuredClone(folders)
  const items = [{ id: '007', name: 'Example.pdf', metadata: { status: 'ready' }, __folder: true }]
  const originalItems = structuredClone(items)
  const query = { folderId: '11', view: 'all', keyword: 'sample', page: '2', tags: ['A', 'B'] }
  const originalQuery = structuredClone(query)
  const rows = folderExplorerRows(folders, items, { scope: 0, kind: 'file' })
  folderExplorerBreadcrumbs(folders, 13)
  parentFolderScope(folders, 13)
  folderExplorerQuery(query, 12)
  assert.deepEqual(folders, originalFolders)
  assert.deepEqual(items, originalItems)
  assert.deepEqual(query, originalQuery)
  assert.equal(rows.at(-1).id, '007')
  assert.equal(rows.at(-1).__explorerKey, 'file:007')
  assert.equal(rows.at(-1).__folder, false)
})

test('route scope distinguishes root and all while rejecting invalid folder IDs', () => {
  assert.equal(folderExplorerScope({ view: 'all', folderId: '12' }), null)
  assert.equal(folderExplorerScope({ folderId: '12' }), 12)
  assert.equal(folderExplorerScope({ folderId: '0' }), 0)
  for (const query of [null, undefined, {}, { folderId: '-1' }, { folderId: [] }, { folderId: '1e2' }, { folderId: '1.5' }, { folderId: '9007199254740992' }, { view: 'unknown' }]) {
    assert.equal(folderExplorerScope(query), 0)
  }
})

test('route updates retain unrelated query values and remove stale navigation options', () => {
  const query = { keyword: 'hello', folderId: '12', view: 'all', sort: 'updated', tags: ['A', 'B'] }
  assert.deepEqual(folderExplorerQuery(query, null), { keyword: 'hello', view: 'all', sort: 'updated', tags: ['A', 'B'] })
  assert.deepEqual(folderExplorerQuery(query, 0), { keyword: 'hello', sort: 'updated', tags: ['A', 'B'] })
  assert.deepEqual(folderExplorerQuery(query, '11'), { keyword: 'hello', folderId: '11', sort: 'updated', tags: ['A', 'B'] })
  assert.deepEqual(folderExplorerQuery(null, 0), {})
})
