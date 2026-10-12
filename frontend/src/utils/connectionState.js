// ===========================================================================
// 全局后端连通性状态。
//
// 为什么需要它：接口失败时每个页面只弹一条 toast，用户滑走就没了痕迹。
// 而在「后端没起来」这种整条链路都断的情况下，各页面会各自渲染成空态
// （"这里还没有内容""还没有速查卡"），看起来像"我的数据没了"。
//
// 这里由 api 拦截器统一登记连通性，由 AppLayout 用一条横幅把「整条链路断了」
// 讲清楚：页面级失败态负责局部，横幅负责全局。
// ===========================================================================
import { ref } from 'vue'

/** 后端是否不可达（false = 正常，或还没失败过） */
export const backendDown = ref(false)
/** 触发降级的原始原因，直接展示给用户看"为什么" */
export const backendDownReason = ref('')

/**
 * 这个错误是否属于「链路层不可达」，而不是业务失败。
 * <p>只有链路层才值得拉全局横幅：4xx 是业务问题（参数不对、没权限），
 * 出来一条"后端没连上"会把用户带偏。
 */
export function isLinkError(err) {
  if (!err) return false
  // 没有 response = 请求根本没到后端：连接被拒、超时、代理挂了
  if (!err.response) return true
  // 5xx = 到了后端但后端自己起不来（如数据库连不上）
  return err.response.status >= 500
}

/** 登记一次链路失败 */
export function markBackendDown(err) {
  backendDown.value = true
  backendDownReason.value = err?.message || '网络错误'
}

/** 有任何一次请求成功就认为链路已恢复（在拦截器里调用，必须廉价） */
export function markBackendUp() {
  if (!backendDown.value) return
  backendDown.value = false
  backendDownReason.value = ''
}
