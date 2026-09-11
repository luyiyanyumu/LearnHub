import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layout/AppLayout.vue'

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
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · IT 学习工作台` : 'IT 学习工作台'
})

export default router
