package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.dyh.learnhub.dto.NoteEditRequest;
import org.dyh.learnhub.entity.Note;
import org.dyh.learnhub.mapper.CategoryMapper;
import org.dyh.learnhub.mapper.NoteMapper;
import org.dyh.learnhub.mapper.TagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoteContentEditServiceTest {
    private final NoteMapper notes = mock(NoteMapper.class);
    private final LearningActivityService activity = mock(LearningActivityService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final NoteService service = new NoteService(notes, mock(CategoryMapper.class), mock(TagMapper.class), activity, events, new NoteContentEditor());
    private final Note note = new Note();

    @BeforeEach void fixture() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Note.class);
        note.setId(12L);
        note.setTitle("保留标题");
        note.setCategoryId(5L);
        note.setContent("这是旧知识。");
    }

    private NoteEditRequest request(NoteEditRequest.Operation... ops) {
        return new NoteEditRequest(NoteContentEditor.hash(note.getContent()), List.of(ops));
    }

    private NoteEditRequest.Operation replace(String text, String value) {
        return new NoteEditRequest.Operation("replace", text, value, null, null, null, null, null, null, null, null);
    }

    @Test void previewIsReadOnlyAndNeverRecordsActivityOrWikiChanges() {
        when(notes.selectById(12L)).thenReturn(note);
        var preview = service.previewEdit(12L, request(replace("旧", "新")));
        assertTrue(preview.changed());
        assertEquals("这是旧知识。", note.getContent());
        verify(notes, never()).selectForEdit(anyLong());
        verify(notes, never()).update(any(), any());
        verifyNoInteractions(activity, events);
    }

    @Test void failedLastOperationCannotPersistTheFirstOperation() {
        when(notes.selectForEdit(12L)).thenReturn(note);
        assertThrows(IllegalArgumentException.class,
                () -> service.editContent(12L, request(replace("旧", "新"), replace("不存在", "替换"))));
        assertEquals("这是旧知识。", note.getContent());
        verify(notes, never()).update(any(), any());
        verifyNoInteractions(activity, events);
    }

    @Test void versionIsCheckedAgainUnderRowLock() {
        var planned = request(replace("旧", "新"));
        note.setContent("已经有人改过正文，旧知识仍然在。");
        when(notes.selectForEdit(12L)).thenReturn(note);
        assertThrows(IllegalArgumentException.class, () -> service.editContent(12L, planned));
        verify(notes, never()).update(any(), any());
        verifyNoInteractions(activity, events);
    }

    @Test void successfulEditUpdatesOnlyContentSummaryAndTime() {
        when(notes.selectForEdit(12L)).thenReturn(note);
        var result = service.editContent(12L, request(replace("旧", "新")));
        assertEquals(NoteContentEditor.hash("这是新知识。"), result.afterHash());
        assertEquals("保留标题", result.title());
        @SuppressWarnings("unchecked") ArgumentCaptor<LambdaUpdateWrapper<Note>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(notes).update(isNull(), update.capture());
        String sql = update.getValue().getSqlSet();
        assertTrue(sql.contains("content="), sql);
        assertTrue(sql.contains("summary="), sql);
        assertTrue(sql.contains("updated_at="), sql);
        assertFalse(sql.contains("title="), sql);
        assertFalse(sql.contains("category_id="), sql);
        verify(notes, never()).deleteNoteTags(anyLong());
        verify(activity).record("note", 12L);
        verify(events).publishEvent(any(KnowledgeChangedEvent.class));
    }

    @Test void noOpDoesNotTouchTimestampOrActivity() {
        when(notes.selectForEdit(12L)).thenReturn(note);
        assertFalse(service.editContent(12L, request(replace("旧", "旧"))).changed());
        verify(notes, never()).update(any(), any());
        verifyNoInteractions(activity, events);
    }
}
