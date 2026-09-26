package org.dyh.learnhub.common;

import java.util.Set;

/**
 * 知识库内容发生变化（笔记 / 速查卡 增删改）。
 * <p>
 * 为什么用事件而不是直接调用：写入路径有两条 —— 页面上的保存，以及智能体「确认执行」写操作。
 * 若在控制器里逐个调用 wiki 刷新，智能体那条路径必然漏掉；
 * 发事件则让 WikiService / KgService 各自订阅，写入方不需要知道有谁在听。
 *
 * @param categoryIds 受影响的分类 id（新分类与旧分类都算，改分类时要刷新两边）
 */
public record KnowledgeChangedEvent(Set<Long> categoryIds, String source) {
}
