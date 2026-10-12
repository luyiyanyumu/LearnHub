package org.dyh.learnhub.vo;

/** itemCount 只统计直接收纳的条目，子目录由前端组树。 */
public record FolderVO(Long id, String kind, String name, Long parentId, long itemCount) { }
