function integerId(value) {
  if (typeof value !== 'number' && typeof value !== 'string') return null
  if (typeof value === 'string' && !/^\d+$/.test(value.trim())) return null
  const id = Number(value)
  return Number.isSafeInteger(id) && id >= 0 ? id : null
}

/** null = all contents, 0 = unfiled, positive = the exact folder. */
export function normalizeFolderScope(value) {
  if (value == null || value === '') return null
  return integerId(value)
}

export function folderScopeParams(value) {
  const scope = normalizeFolderScope(value)
  return scope === null ? {} : { folderId: scope }
}

export function validItemIds(values) {
  return [...new Set((Array.isArray(values) ? values : []).map(integerId).filter(id => id > 0))]
}

/** Clone the flat API data; orphaned/cyclic folders remain reachable at the root. */
export function buildFolderTree(folders) {
  const nodes = new Map()
  for (const item of Array.isArray(folders) ? folders : []) {
    const id = integerId(item?.id)
    if (!id || nodes.has(id)) continue
    nodes.set(id, {
      ...item,
      id,
      name: String(item.name || '未命名文件夹'),
      parentId: integerId(item.parentId) || 0,
      itemCount: Number.isSafeInteger(Number(item.itemCount)) && Number(item.itemCount) >= 0 ? Number(item.itemCount) : 0,
      children: [],
    })
  }
  for (const node of nodes.values()) {
    if (!nodes.has(node.parentId) || node.parentId === node.id) node.parentId = 0
    const visited = new Set([node.id])
    let parent = nodes.get(node.parentId)
    while (parent) {
      if (visited.has(parent.id)) {
        node.parentId = 0
        break
      }
      visited.add(parent.id)
      parent = nodes.get(parent.parentId)
    }
  }
  const roots = []
  for (const node of nodes.values()) {
    const parent = nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}

/** Each option has id/value, name, path, depth and direct itemCount. */
export function flattenFolderOptions(folders, { includeUnfiled = false } = {}) {
  const options = includeUnfiled ? [{ id: 0, value: 0, name: '未收纳', label: '未收纳', path: '未收纳', depth: 0, itemCount: 0 }] : []
  const stack = buildFolderTree(folders).map(node => ({ node, depth: 0, prefix: '' })).reverse()
  while (stack.length) {
    const { node, depth, prefix } = stack.pop()
    const path = prefix ? `${prefix} / ${node.name}` : node.name
    options.push({ ...node, value: node.id, label: path, path, depth })
    for (let index = node.children.length - 1; index >= 0; index--) {
      stack.push({ node: node.children[index], depth: depth + 1, prefix: path })
    }
  }
  return options
}

export function folderName(folders, value) {
  const scope = normalizeFolderScope(value)
  if (scope === null) return '全部'
  if (scope === 0) return '未收纳'
  return flattenFolderOptions(folders).find(folder => folder.id === scope)?.name || '文件夹'
}

export const folderOptions = flattenFolderOptions

export function folderPath(folders, value) {
  const scope = normalizeFolderScope(value)
  if (scope === null || scope === 0) return '未收纳'
  return flattenFolderOptions(folders).find(folder => folder.id === scope)?.path || '未收纳'
}

export function resolveFolderScope(folders, value) {
  const scope = normalizeFolderScope(value)
  return scope > 0 && !flattenFolderOptions(folders).some(folder => folder.id === scope) ? null : scope
}
