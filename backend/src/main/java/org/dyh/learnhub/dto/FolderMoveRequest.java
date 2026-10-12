package org.dyh.learnhub.dto;

import java.util.List;

/** folderId=null 表示移出文件夹，ids 中的所有条目要么全部移动，要么完全不动。 */
public record FolderMoveRequest(String kind, List<Long> ids, Long folderId) { }
