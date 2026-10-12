import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFolderTree, flattenFolderOptions, folderName, folderPath, folderScopeParams, normalizeFolderScope, resolveFolderScope, validItemIds } from './folders.js'

const folders = [
  { id: 12, parentId: 11, name: '论文', itemCount: 2 },
  { id: 11, parentId: 0, name: '研究', itemCount: 1 },
  { id: 13, parentId: 12, name: '待阅读', itemCount: 3 },
  { id: 21, parentId: 0, name: '课程', itemCount: 0 },
]

test('query scope keeps all and unfiled distinct, including string route parameters', () => {
  for (const value of [null, undefined, '', -1, 'NaN', NaN, true, [], '1e3', '1.5']) {
    assert.equal(normalizeFolderScope(value), null)
    assert.deepEqual(folderScopeParams(value), {})
  }
  assert.equal(normalizeFolderScope(' 0 '), 0)
  assert.deepEqual(folderScopeParams(0), { folderId: 0 })
  assert.deepEqual(folderScopeParams('12'), { folderId: 12 })
  assert.equal(resolveFolderScope(folders, '12'), 12)
  assert.equal(resolveFolderScope(folders, 999), null)
  assert.equal(resolveFolderScope(folders, 0), 0)
})

test('out-of-order flat data produces complete hierarchical paths without mutating API data', () => {
  const before = JSON.stringify(folders)
  const tree = buildFolderTree(folders)
  assert.deepEqual(tree.map(folder => folder.id), [11, 21])
  assert.equal(tree[0].children[0].id, 12)
  assert.deepEqual(flattenFolderOptions(folders).map(folder => [folder.id, folder.path, folder.depth]), [
    [11, '研究', 0], [12, '研究 / 论文', 1], [13, '研究 / 论文 / 待阅读', 2], [21, '课程', 0],
  ])
  assert.equal(folderPath(folders, 13), '研究 / 论文 / 待阅读')
  assert.equal(folderName(folders, '12'), '论文')
  assert.equal(folderName(folders, null), '全部')
  assert.equal(folderName(folders, 0), '未收纳')
  assert.equal(folderPath(folders, null), '未收纳')
  assert.equal(folderPath(folders, 999), '未收纳')
  assert.equal(flattenFolderOptions(folders, { includeUnfiled: true })[0].id, 0)
  assert.equal(JSON.stringify(folders), before)
})

test('broken parents and cycles never hide contents or create recursive UI loops', () => {
  const damaged = [
    { id: 1, parentId: 2, name: 'A' }, { id: 2, parentId: 1, name: 'B' },
    { id: 3, parentId: 999, name: '孤立' }, { id: 4, parentId: 4, name: '自身' },
    { id: '5', parentId: '3', name: '孩子', itemCount: '4' },
    { id: 5, name: '重复 ID' }, null, { id: 0 }, { id: -1 },
  ]
  const options = flattenFolderOptions(damaged)
  assert.deepEqual([...options.map(folder => folder.id)].sort((a, b) => a - b), [1, 2, 3, 4, 5])
  assert.equal(options.find(folder => folder.id === 5).path, '孤立 / 孩子')
  assert.equal(options.find(folder => folder.id === 5).itemCount, 4)
  assert.deepEqual(buildFolderTree(null), [])
})

test('bulk moves reject invalid IDs, retain every valid selected item once and never send root as an item', () => {
  assert.deepEqual(validItemIds([1, '2', 1, 0, null, undefined, true, 'oops', -1, 2.5, 3]), [1, 2, 3])
  assert.deepEqual(validItemIds(null), [])
  assert.deepEqual(validItemIds(['9007199254740992', Number.MAX_SAFE_INTEGER]), [Number.MAX_SAFE_INTEGER])
})
