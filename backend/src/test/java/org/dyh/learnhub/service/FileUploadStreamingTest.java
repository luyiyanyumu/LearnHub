package org.dyh.learnhub.service;

import org.dyh.learnhub.common.GlobalExceptionHandler;
import org.dyh.learnhub.controller.FileController;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileUploadStreamingTest {
    @TempDir Path temp;
    private final FileInfoMapper files = mock(FileInfoMapper.class);
    private final FolderService folders = mock(FolderService.class);
    private final DocumentTextService texts = mock(DocumentTextService.class);
    private final FileStorageService storage = new FileStorageService(files, null, texts, null, null, null,
            mock(ApplicationEventPublisher.class), mock(LearningActivityService.class), null, folders);

    @Test void multipartWritesTheStreamAndKeepsFolderMetadata() throws Exception {
        byte[] data = {1, 2, 3};
        var upload = mock(MultipartFile.class);
        when(upload.getOriginalFilename()).thenReturn("synthetic.pdf");
        when(upload.getInputStream()).thenReturn(new ByteArrayInputStream(data));
        when(folders.requireTarget("file", 12L)).thenReturn(12L);
        when(texts.extract(any(Path.class), eq("pdf"), eq(3L)))
                .thenReturn(new DocumentTextService.Extracted("empty", null, null, 0));

        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", temp.toString());
            FileInfo saved = storage.upload(upload, 7L, 12L);
            assertArrayEquals(data, Files.readAllBytes(temp.resolve("uploads").resolve(saved.getStoreName())));
            assertEquals(3L, saved.getSize());
            assertEquals(12L, saved.getFolderId());
            assertEquals(7L, saved.getCategoryId());
            assertEquals("empty", saved.getTextStatus());
            verify(upload).getInputStream();
            verify(upload, never()).getBytes();
            verify(files).insert(saved);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test void oversizedPdfKeepsTheOriginalAndSkipsAllExtractionStages() throws Exception {
        Path source = Files.createDirectories(temp.resolve("uploads")).resolve("oversized.pdf");
        long size = 201L * 1024 * 1024;
        try (var file = new RandomAccessFile(source.toFile(), "rw")) {
            file.setLength(size);
        }
        FileInfo info = new FileInfo();
        info.setId(7L);
        info.setStoreName(source.getFileName().toString());
        info.setExt("pdf");
        info.setSize(size);
        when(files.selectById(7L)).thenReturn(info);
        var media = mock(DocumentMediaProcessor.class);
        storage.setMediaProcessor(media);

        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", temp.toString());
            var result = storage.reextract(7L);
            assertEquals("skipped", result.get("textStatus"));
            assertTrue(result.get("textError").toString().contains("200 MiB"));
            assertEquals(size, Files.size(source));
            verifyNoInteractions(texts, media);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test void multipartSizeExceptionReturnsStructured413() throws Exception {
        var rejected = mock(FileStorageService.class);
        when(rejected.upload(any(), isNull(), isNull())).thenThrow(new MaxUploadSizeExceededException(-1));
        var mvc = MockMvcBuilders.standaloneSetup(new FileController(rejected, null, null))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", new byte[]{1})))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.msg").value("文件超过服务器上传大小上限，请拆分文件后重试。"));
    }
}
