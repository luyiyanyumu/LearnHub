package org.dyh.learnhub.vo;

import java.util.List;

/** 只返回改动片段，长笔记无需在模型输出中重写或传输全文。 */
public record NoteEditVO(Long noteId, String title, String beforeHash, String afterHash,
                         boolean changed, List<Change> changes) {
    public record Change(String action, String label, int count, String before, String after) {
    }
}
