package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.dto.NoteDTO;
import org.dyh.learnhub.entity.Note;
import org.dyh.learnhub.mapper.CategoryMapper;
import org.dyh.learnhub.mapper.NoteMapper;
import org.dyh.learnhub.mapper.TagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoteFolderBindingTest {
    private final NoteMapper notes = mock(NoteMapper.class);
    private final TagMapper tags = mock(TagMapper.class);
    private final FolderService folders = mock(FolderService.class);
    private final NoteService service = new NoteService(notes, mock(CategoryMapper.class), tags,
            mock(LearningActivityService.class), mock(ApplicationEventPublisher.class), new NoteContentEditor(), folders);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void prepare() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "note-folder-test"), Note.class);
        var note = new Note(); note.setId(1L); note.setTitle("笔记"); note.setContent("正文"); note.setFolderId(5L);
        when(notes.selectById(1L)).thenReturn(note);
        when(tags.selectByNoteId(1L)).thenReturn(List.of());
    }

    @Test void omittedFolderIdInLegacyAutosavePreservesExistingLocation() throws Exception {
        var dto = json.readValue("{\"title\":\"笔记\",\"content\":\"新正文\"}", NoteDTO.class);
        assertThat(dto.isFolderIdProvided()).isFalse();
        service.update(1L, dto);
        assertThat(update().getSqlSet()).doesNotContain("folder_id");
        verifyNoInteractions(folders);
        assertThat(service.detail(1L).getFolderId()).isEqualTo(5);
    }

    @Test void explicitNullUnfilesButClientCannotSpoofThePresenceFlag() throws Exception {
        var dto = json.readValue("{\"title\":\"笔记\",\"folderId\":null,\"folderIdProvided\":false}", NoteDTO.class);
        assertThat(dto.isFolderIdProvided()).isTrue();
        service.update(1L, dto);
        assertThat(update().getSqlSet()).contains("folder_id=");
        assertThat(update().getParamNameValuePairs().values()).contains((Object) null);
        verify(folders).requireTarget("note", null);
    }

    @Test void explicitTargetIsValidatedBeforeNoteChanges() throws Exception {
        var dto = json.readValue("{\"title\":\"笔记\",\"folderId\":6}", NoteDTO.class);
        when(folders.requireTarget("note", 6L)).thenThrow(new IllegalArgumentException("错误类型"));
        assertThatThrownBy(() -> service.update(1L, dto)).isInstanceOf(IllegalArgumentException.class);
        verify(notes, never()).update(isNull(), any());
        verify(notes, never()).deleteNoteTags(anyLong());
    }

    @Test void newNoteStoresValidatedFolder() {
        NoteDTO dto = new NoteDTO(); dto.setTitle("新笔记"); dto.setFolderId(5L);
        when(folders.requireTarget("note", 5L)).thenReturn(5L);
        when(notes.insert(any(Note.class))).thenAnswer(call -> { ((Note) call.getArgument(0)).setId(1L); return 1; });
        service.save(dto);
        ArgumentCaptor<Note> capture = ArgumentCaptor.forClass(Note.class);
        verify(notes).insert(capture.capture());
        assertThat(capture.getValue().getFolderId()).isEqualTo(5);
    }

    @Test void allUnfiledAndExactFolderFiltersRetainCategoriesAndTags() {
        when(notes.selectPage(any(Page.class), any())).thenAnswer(call -> call.getArgument(0));
        when(notes.selectNoteIdsByTag(9L)).thenReturn(List.of(1L));
        service.page(7L, 9L, "正文", 1, 10, 0L);
        var query = query();
        assertThat(query.getSqlSegment()).contains("folder_id IS NULL", "category_id =", "id IN", "title LIKE", "content LIKE");
        assertThat(query.getSqlSelect()).contains("folder_id").doesNotContain("content");
        clearInvocations(notes);
        service.page(null, null, null, 1, 10, 5L);
        assertThat(query().getSqlSegment()).contains("folder_id =");
        clearInvocations(notes);
        service.page(null, null, null, 1, 10);
        assertThat(query().getSqlSegment()).doesNotContain("folder_id");
    }

    @SuppressWarnings("unchecked") private LambdaUpdateWrapper<Note> update() {
        ArgumentCaptor<LambdaUpdateWrapper<Note>> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(notes).update(isNull(), capture.capture()); return capture.getValue();
    }
    @SuppressWarnings("unchecked") private LambdaQueryWrapper<Note> query() {
        ArgumentCaptor<LambdaQueryWrapper<Note>> capture = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(notes).selectPage(any(Page.class), capture.capture()); return capture.getValue();
    }
}
