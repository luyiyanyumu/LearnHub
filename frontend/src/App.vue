<script setup>
// Element Plus 的语言包。配合 main.js 的「按需引入」，
// 不能用 app.use(ElementPlus, { locale }) 那种全量注册的方式下发 locale，
// 改成在根组件套一层 <el-config-provider>（这是官方推荐的按需引入搭配方式）。
// 少了它，分页器/空状态/日期选择器等内置文案会变成英文。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
