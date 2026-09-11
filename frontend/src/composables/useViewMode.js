import { ref } from 'vue'

/**
 * 跨组件的阅读视图状态。
 * focusMode：编辑页「专注模式」—— AppLayout 据此隐藏左侧导航，编辑页隐藏大纲。
 * 模块级单例：编辑页写、布局层读，路由离开时由编辑页负责复位。
 */
export const focusMode = ref(false)
