const RECOVERY_KEY = 'lh-route-load-recovery'

function isChunkLoadError(error) {
  return error?.name === 'ChunkLoadError'
    || /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS|Loading (?:CSS )?chunk .+ failed/i.test(error?.message || '')
}

/** Old tabs can reference route chunks removed by a new deployment. */
export function installRouteLoadRecovery(router, { location, storage, notify }) {
  let latestTarget = null
  let recovering = false

  const removeBefore = router.beforeEach((to) => {
    latestTarget = to.fullPath
  })

  const removeAfter = router.afterEach((to, _from, failure) => {
    if (failure) return
    latestTarget = to.fullPath
    recovering = false
    try {
      storage.removeItem(RECOVERY_KEY)
    } catch {
      // Unavailable storage must not prevent normal navigation.
    }
  })

  const removeError = router.onError((error, to, from) => {
    // A slower failed import must not undo a newer navigation.
    if (latestTarget && latestTarget !== to?.fullPath) return
    if (!isChunkLoadError(error)) {
      notify('页面切换失败，请稍后重试。')
      return
    }

    // The editor stays mounted after a failed route load. Keep its draft,
    // including changes typed while the failed import was pending.
    if (from?.name === 'noteEdit' || from?.name === 'noteNew') {
      notify('页面资源加载失败，请先保存当前笔记，再刷新页面后重试。')
      return
    }
    if (recovering) return

    try {
      const href = router.resolve(to.fullPath).href
      if (storage.getItem(RECOVERY_KEY) !== href) {
        // Persist before navigating so a broken deployment cannot loop.
        // If storage is blocked, fall back to the visible error below.
        storage.setItem(RECOVERY_KEY, href)
        recovering = true
        location.assign(href)
        return
      }
    } catch {
      recovering = false
    }
    notify('页面资源加载失败，请刷新页面后重试。')
  })

  return () => {
    removeBefore()
    removeAfter()
    removeError()
  }
}
