package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileFolderBindingTest {
    private final FileInfoMapper files = mock(FileInfoMapper.class);
    private final FolderService folders = mock(FolderService.class);
    private final FileStorageService service = new FileStorageService(files, null, null, null, null, null, null, null, null, folders);

    @BeforeEach void tables() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "file-folder-test"), FileInfo.class);
        when(files.selectList(any())).thenReturn(List.of());
    }

    @Test void allUnfiledAndExactFolderFiltersRetainCategoryAndKeyword() {
        service.list(7L, "论文", 0L);
        assertThat(query().getSqlSegment()).contains("folder_id IS NULL", "category_id =", "origin_name LIKE");
        clearInvocations(files);
        service.list(null, null, 5L);
        assertThat(query().getSqlSegment()).contains("folder_id =");
        clearInvocations(files);
        service.list(null, null);
        assertThat(query().getSqlSegment()).doesNotContain("folder_id");
    }

    @Test void invalidUploadTargetFailsBeforeWritingMetadataOrPhysicalFile() {
        when(folders.requireTarget("file", 5L)).thenThrow(new IllegalArgumentException("文件夹类型错误"));
        assertThatThrownBy(() -> service.uploadBytes("demo.md", new byte[] {1}, null, 5L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(files, never()).insert(any(FileInfo.class));
    }

    @Test void listAndDetailMapsExposeFolderAndMetadataUpdatesDoNotOverwriteIt() {
        FileInfo info = new FileInfo(); info.setId(1L); info.setFolderId(5L);
        assertThat(service.toMap(info)).containsEntry("folderId", 5L);
        var table = TableInfoHelper.getTableInfo(FileInfo.class);
        assertThat(table.getAllSqlSet(false, "et.")).doesNotContain("folder_id");
    }

    @SuppressWarnings("unchecked") private LambdaQueryWrapper<FileInfo> query() {
        ArgumentCaptor<LambdaQueryWrapper<FileInfo>> capture = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(files).selectList(capture.capture()); return capture.getValue();
    }
}
