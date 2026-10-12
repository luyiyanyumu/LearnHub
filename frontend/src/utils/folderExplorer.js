import { buildFolderTree, flattenFolderOptions, normalizeFolderScope } from './folders.js'

/** Root is the collection's unfiled contents; null is its explicit aggregate view. */
export function folderExplorerScope(query) {
  return query?.view === 'all' ? null : (normalizeFolderScope(query?.folderId) ?? 0)
}

/** Keep searches and unrelated page options while replacing only the folder location. */
export function folderExplorerQuery(query, scope) {
  const next = { ...(query && typeof query === 'object' && !Array.isArray(query) ? query : {}) }
  const normalized = normalizeFolderScope(scope)
  delete next.folderId
  delete next.view
  if (normalized === null) next.view = 'all'
  else if (normalized > 0) next.folderId = String(normalized)
  return next
}

export function isExplorerFolder(row) {
  return row?.__folder === true
}

/** Folders precede the already-filtered content page; counts remain direct API counts. */
export function folderExplorerRows(folders, items, { scope, keyword = '', kind = 'note' } = {}) {
  const normalized = normalizeFolderScope(scope)
  let childFolders = []
  if (normalized === 0) childFolders = buildFolderTree(folders)
  else if (normalized > 0) childFolders = flattenFolderOptions(folders).find(folder => folder.id === normalized)?.children || []
  const search = String(keyword ?? '').trim().toLocaleLowerCase()
  const rows = childFolders
    .filter(folder => !search || folder.name.toLocaleLowerCase().includes(search))
    .map(folder => {
      // Element Plus tables treat a children field as nested rows. This is a flat
      // directory page: retain only the direct child count, not their row data.
      const { children, ...data } = folder
      return {
        ...data,
        __folder: true,
        __explorerKey: `folder:${folder.id}`,
        name: folder.name,
        childrenCount: children.length,
        itemCount: folder.itemCount,
      }
    })

  const usedKeys = new Set(rows.map(row => row.__explorerKey))
  for (const [index, item] of (Array.isArray(items) ? items : []).entries()) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue
    const baseKey = `${kind}:${item.id == null ? `missing:${index}` : String(item.id)}`
    let key = baseKey
    let suffix = 2
    while (usedKeys.has(key)) key = `${baseKey}:${suffix++}`
    usedKeys.add(key)
    rows.push({ ...item, __folder: false, __explorerKey: key })
  }
  return rows
}

/** Follow only normalized parents, making orphaned and cyclic API data safe to navigate. */
export function folderExplorerBreadcrumbs(folders, scope) {
  const normalized = normalizeFolderScope(scope)
  if (!(normalized > 0)) return []
  const byId = new Map(flattenFolderOptions(folders).map(folder => [folder.id, folder]))
  const path = []
  const visited = new Set()
  let folder = byId.get(normalized)
  while (folder && !visited.has(folder.id)) {
    visited.add(folder.id)
    path.unshift({ id: folder.id, name: folder.name })
    folder = byId.get(folder.parentId)
  }
  return path
}

export function parentFolderScope(folders, scope) {
  const normalized = normalizeFolderScope(scope)
  if (!(normalized > 0)) return 0
  return flattenFolderOptions(folders).find(folder => folder.id === normalized)?.parentId || 0
}
