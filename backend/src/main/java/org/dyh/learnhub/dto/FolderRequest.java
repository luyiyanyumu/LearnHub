package org.dyh.learnhub.dto;

/** 创建时 kind/name 必填，更新时省略的字段保持不变；parentId=0 为根目录。 */
public record FolderRequest(String kind, String name, Long parentId) { }
