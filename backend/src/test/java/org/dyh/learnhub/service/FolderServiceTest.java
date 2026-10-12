package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.dto.FolderCountRow;
import org.dyh.learnhub.dto.FolderMoveRequest;
import org.dyh.learnhub.dto.FolderRequest;
import org.dyh.learnhub.entity.ContentFolder;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.entity.Note;
import org.dyh.learnhub.mapper.ContentFolderMapper;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.dyh.learnhub.mapper.NoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolderServiceTest {
    private final ContentFolderMapper folders = mock(ContentFolderMapper.class);
    private final NoteMapper notes = mock(NoteMapper.class);
    private final FileInfoMapper files = mock(FileInfoMapper.class);
    private final FolderService service = new FolderService(folders, notes, files);

    @BeforeEach void tables() {
        for (Class<?> type : List.of(ContentFolder.class, Note.class, FileInfo.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "folders-test"), type);
        }
        when(folders.lockKind(anyString())).thenReturn(List.of());
    }

    @Test void flatListUsesDirectCountsAndKeepsTheTwoKindsSeparate() {
        var a = folder(1, "note", "学习", 0);
        var b = folder(2, "note", "算法", 1);
        FolderCountRow count = new FolderCountRow(); count.setFolderId(2L); count.setItemCount(3);
        when(folders.selectList(any())).thenReturn(List.of(a, b));
        when(folders.noteCounts()).thenReturn(List.of(count));
        var list = service.list("note");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).itemCount()).isZero();
        assertThat(list.get(1).parentId()).isEqualTo(1);
        assertThat(list.get(1).itemCount()).isEqualTo(3);
        verify(folders, never()).fileCounts();
    }

    @Test void creatingNormalizesTheNameAndDefaultsToRoot() {
        when(folders.insert(any(ContentFolder.class))).thenAnswer(call -> {
            ((ContentFolder) call.getArgument(0)).setId(3L); return 1;
        });
        var result = service.create(new FolderRequest("file", "  论文  ", null));
        assertThat(result.name()).isEqualTo("论文");
        assertThat(result.parentId()).isZero();
        assertThat(result.kind()).isEqualTo("file");
        verify(folders).lockKind("file");
        verifyNoInteractions(notes, files);
    }

    @Test void duplicateSiblingIsRejectedButSameNameUnderDifferentParentsIsAllowed() {
        var root = folder(1, "note", "根", 0);
        var child = folder(2, "note", "Algorithms", 1);
        when(folders.lockKind("note")).thenReturn(List.of(root, child));
        assertThatThrownBy(() -> service.create(new FolderRequest("note", "algorithms", 1L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同名");
        verify(folders, never()).insert(any(ContentFolder.class));
        service.create(new FolderRequest("note", "Algorithms", 0L));
        verify(folders).insert(any(ContentFolder.class));
    }

    @Test void validatesKindsNamesAndParentsBeforeWriting() {
        for (String kind : Arrays.asList(null, "wiki", "NOTE", "")) {
            assertThatThrownBy(() -> service.create(new FolderRequest(kind, "目录", null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String name : List.of(" ", "a/b", "a\\b", "a\nb", "x".repeat(101))) {
            assertThatThrownBy(() -> service.create(new FolderRequest("note", name, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.create(new FolderRequest("note", "目录", -1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new FolderRequest("note", "目录", 99L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("父文件夹");
        verify(folders, never()).insert(any(ContentFolder.class));
    }

    @Test void parentMustBelongToTheSameKind() {
        when(folders.selectById(4L)).thenReturn(folder(4, "file", "资料", 0));
        assertThatThrownBy(() -> service.requireTarget("note", 4L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateFilter("note", 4L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new FolderRequest("note", "子目录", 4L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void renameKeepsParentAndMoveToRootIsSupported() {
        var entry = folder(2, "note", "旧名", 1);
        var parent = folder(1, "note", "父目录", 0);
        when(folders.selectById(2L)).thenReturn(entry);
        when(folders.lockKind("note")).thenReturn(List.of(parent, entry));
        assertThat(service.update(2L, new FolderRequest(null, "新名", null)).parentId()).isEqualTo(1);
        assertThat(service.update(2L, new FolderRequest(null, null, 0L)).parentId()).isZero();
        assertThat(entry.getName()).isEqualTo("新名");
    }

    @Test void forbidsMovingIntoSelfDescendantOrAnotherKind() {
        var root = folder(1, "note", "根", 0);
        var child = folder(2, "note", "子", 1);
        when(folders.selectById(1L)).thenReturn(root);
        when(folders.lockKind("note")).thenReturn(List.of(root, child));
        for (long target : List.of(1L, 2L)) {
            assertThatThrownBy(() -> service.update(1L, new FolderRequest(null, null, target)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("自己");
        }
        assertThatThrownBy(() -> service.update(1L, new FolderRequest("file", "根", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("类型");
        verify(folders, never()).updateById(any(ContentFolder.class));
    }

    @Test void refusesDeletingAParentWithoutChangingContents() {
        var root = folder(1, "note", "根", 0);
        when(folders.selectById(1L)).thenReturn(root);
        when(folders.lockKind("note")).thenReturn(List.of(root, folder(2, "note", "子", 1)));
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("子文件夹");
        verifyNoInteractions(notes, files);
        verify(folders, never()).deleteById(anyLong());
    }

    @Test void deletingLeafUnfilesOnlyItsOwnKindAndNeverDeletesContent() {
        var leaf = folder(4, "file", "资料", 0);
        when(folders.selectById(4L)).thenReturn(leaf);
        when(folders.lockKind("file")).thenReturn(List.of(leaf));
        when(files.update(isNull(), any())).thenReturn(3);
        assertThat(service.delete(4L)).isEqualTo(3);
        var wrapper = fileUpdate();
        assertThat(wrapper.getSqlSet()).contains("folder_id=");
        assertThat(wrapper.getParamNameValuePairs().values()).contains((Object) null);
        assertThat(wrapper.getSqlSegment()).contains("folder_id =");
        verify(files, never()).deleteById(anyLong());
        verifyNoInteractions(notes);
        verify(folders).deleteById(4L);
    }

    @Test void batchMoveLocksValidatesAllAndWritesOnlyOnce() {
        when(folders.selectForUpdate(4L)).thenReturn(folder(4, "note", "笔记", 0));
        when(notes.lockIdsForFolderMove(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
        assertThat(service.moveItems(new FolderMoveRequest("note", List.of(2L, 1L, 2L), 4L))).isEqualTo(2);
        var wrapper = noteUpdate();
        assertThat(wrapper.getSqlSet()).contains("folder_id=");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(4L);
        assertThat(wrapper.getSqlSegment()).contains("id IN");
        verify(notes, times(1)).update(isNull(), any());
        verifyNoInteractions(files);
    }

    @Test void missingItemOrWrongTargetPreventsAnyPartialMove() {
        when(notes.lockIdsForFolderMove(List.of(1L, 2L))).thenReturn(List.of(1L));
        assertThatThrownBy(() -> service.moveItems(new FolderMoveRequest("note", List.of(1L, 2L), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("部分内容");
        when(folders.selectForUpdate(4L)).thenReturn(folder(4, "file", "资料", 0));
        assertThatThrownBy(() -> service.moveItems(new FolderMoveRequest("note", List.of(1L), 4L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("类型");
        verify(notes, never()).update(isNull(), any());
        verify(files, never()).update(isNull(), any());
    }

    @Test void nullTargetUnfilesAndIdsAreBoundedAndValidated() {
        when(files.lockIdsForFolderMove(List.of(1L))).thenReturn(List.of(1L));
        assertThat(service.moveItems(new FolderMoveRequest("file", List.of(1L), null))).isEqualTo(1);
        assertThat(fileUpdate().getParamNameValuePairs().values()).contains((Object) null);
        for (List<Long> ids : List.of(List.<Long>of(), List.of(0L), List.of(-1L),
                LongStream.rangeClosed(1, 501).boxed().toList())) {
            assertThatThrownBy(() -> service.moveItems(new FolderMoveRequest("note", ids, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.moveItems(new FolderMoveRequest("note", Arrays.asList(1L, null), null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(notes);
    }

    @Test void targetValidationLocksPositiveIdAndNormalizesZero() {
        when(folders.selectForUpdate(4L)).thenReturn(folder(4, "file", "资料", 0));
        assertThat(service.requireTarget("file", 4L)).isEqualTo(4);
        assertThat(service.requireTarget("file", 0L)).isNull();
        assertThat(service.requireTarget("file", null)).isNull();
        verify(folders).selectForUpdate(4L);
        assertThatThrownBy(() -> service.requireTarget("file", -1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Note> noteUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<Note>> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(notes).update(isNull(), capture.capture());
        return capture.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<FileInfo> fileUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<FileInfo>> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(files).update(isNull(), capture.capture());
        return capture.getValue();
    }

    private static ContentFolder folder(long id, String kind, String name, long parent) {
        var folder = new ContentFolder(); folder.setId(id); folder.setKind(kind); folder.setName(name); folder.setParentId(parent);
        return folder;
    }
}
