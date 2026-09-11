package org.dyh.learnhub.dto;

import lombok.Data;
import org.dyh.learnhub.entity.Tag;

import java.time.LocalDateTime;

/**
 * note_tag ⋈ tag 的连表投影行（一篇笔记 → 一个标签）。
 *
 * <p>用途只有一个：把「列表页每篇笔记各查一次标签」（N+1）换成一次连表查询，
 * 回到 Java 再按 noteId 分组。见 {@code TagMapper#selectByNoteIds}。
 *
 * <p>为什么不直接返回 {@code List<Map<String, Object>>}：列名一旦写错要到运行时才炸，
 * 而且拿字段还得强转。用一个显式的小类，编译期就能查出来。
 */
@Data
public class NoteTagRow {

    /** 笔记 id（连表时来自 note_tag.note_id） */
    private Long noteId;

    /** 标签 id */
    private Long tagId;

    private String name;

    private LocalDateTime createdAt;

    /** 还原成前端要的 Tag 结构 */
    public Tag toTag() {
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setName(name);
        tag.setCreatedAt(createdAt);
        return tag;
    }
}
