import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppLayout from '../layout/AppLayout.vue'
import { installRouteLoadRecovery } from '../utils/routeLoadRecovery'

const routes = [
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '总览' } },
      { path: 'notes', name: 'notes', component: () => import('../views/NoteList.vue'), meta: { title: '笔记' } },
      { path: 'notes/new', name: 'noteNew', component: () => import('../views/NoteEdit.vue'), meta: { title: '新建笔记' } },
      { path: 'notes/:id', name: 'noteEdit', component: () => import('../views/NoteEdit.vue'), meta: { title: '编辑笔记' } },
      { path: 'refs', name: 'refs', component: () => import('../views/QuickRefs.vue'), meta: { title: '速查卡' } },
      { path: 'files', name: 'files', component: () => import('../views/FileLibrary.vue'), meta: { title: '资料库' } },
      { path: 'knowledge', name: 'knowledge', component: () => import('../views/Knowledge.vue'), meta: { title: '知识库' } },
    { path: 'agent', name: 'agent', component: () => import('../views/AgentSessions.vue'), meta: { title: '智能体' } },
    { path: 'code', name: 'code', component: () => import('../views/CodeLibrary.vue'), meta: { title: '代码库' } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

installRouteLoadRecovery(router, {
  location: window.location,
  // Access sessionStorage inside the recovery handler's try/catch: some
  // browser privacy settings throw even when reading the storage property.
  storage: {
    getItem: (key) => window.sessionStorage.getItem(key),
    setItem: (key, value) => window.sessionStorage.setItem(key, value),
    removeItem: (key) => window.sessionStorage.removeItem(key),
  },
  notify: (message) => ElMessage.error({ message, duration: 6000, showClose: true }),
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · IT 学习工作台` : 'IT 学习工作台'
})

export default router
