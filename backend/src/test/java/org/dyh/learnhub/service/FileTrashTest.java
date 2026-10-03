package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 删除资料必须**可恢复**：磁盘文件移进 {@code uploads/.trash}，而不是直接删。
 *
 * <p>起因是一次真实的误删：一条 14MB 的资料被删掉后，正文虽然还在知识库里，
 * <b>原文件本身再也拿不回来</b>。而"删资料"是不可逆操作、误删代价又大 ——
 * 所以给它一次后悔的机会。
 *
 * <p>{@code storageDir()} 取的是 {@code user.dir}/uploads，测试里把它指到临时目录，
 * 避免碰到真实的 uploads（真实资料不能被测试搅动）。
 */
class FileTrashTest {

    private Path work;
    private String originalUserDir;
    private FileStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");
        work = Files.createTempDirectory("lh-trash-test");
        System.setProperty("user.dir", work.toString());

        FileInfoMapper mapper = mock(FileInfoMapper.class);
        // 参数顺序与字段声明一致：mapper, categoryMapper, documentTextService, pdfLayoutExtractor, events, learningActivity
        // delete() 会 publishChanged（通知知识库索引刷新），所以 events 要给个 mock，其余用不到
        // 最后一个是 KbChunkMapper（改名时同步知识块标题用），本测试不涉及改名 → null
        service = new FileStorageService(mapper, null, null, null,
                mock(org.springframework.context.ApplicationEventPublisher.class), null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setProperty("user.dir", originalUserDir);
        if (work != null && Files.exists(work)) {
            try (var walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 临时目录清理失败不影响测试结论
                    }
                });
            }
        }
    }

    private void writeUpload(String storeName, String content) throws Exception {
        Path dir = work.resolve("uploads");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(storeName), content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("删除后文件进回收站（可恢复），而不是消失")
    void deleteMovesFileToTrash() throws Exception {
        writeUpload("abc123.pdf", "%PDF-1.4 假装是内容");

        FileInfo info = new FileInfo();
        info.setId(9L);
        info.setOriginName("面试资料.pdf");
        info.setStoreName("abc123.pdf");
        setMapper(info);

        service.delete(9L);

        // 原位置已经没有了
        assertFalse(Files.exists(work.resolve("uploads/abc123.pdf")), "原文件还在，说明没被移走");
        // 回收站里有，且文件名带删除时间戳
        Path trashDir = work.resolve("uploads").resolve(FileStorageService.TRASH_DIR);
        assertTrue(Files.isDirectory(trashDir), "没有创建回收站目录");
        try (var files = Files.list(trashDir)) {
            List<Path> inTrash = files.toList();
            assertEquals(1, inTrash.size(), "回收站里的文件数不对");
            String name = inTrash.get(0).getFileName().toString();
            assertTrue(name.startsWith("abc123.pdf.deleted-"), "回收站文件名不对：" + name);
            assertTrue(name.matches("abc123\\.pdf\\.deleted-\\d{13,}"), "缺少时间戳：" + name);
            // 内容完好（恢复后就是这份文件）
            assertEquals("%PDF-1.4 假装是内容", Files.readString(inTrash.get(0), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("回收站可列出（供人工恢复），空时返回空表而不是报错")
    void trashListsDeletedFiles() throws Exception {
        assertTrue(service.trash().isEmpty(), "回收站为空时应返回空列表");

        writeUpload("x.pdf", "1");
        writeUpload("y.docx", "22");
        setMapper(fileInfo(1L, "x.pdf"));
        service.delete(1L);
        setMapper(fileInfo(2L, "y.docx"));
        service.delete(2L);

        List<Map<String, Object>> listed = service.trash();
        assertEquals(2, listed.size(), "回收站应列出 2 个文件");
        for (Map<String, Object> m : listed) {
            assertTrue(String.valueOf(m.get("name")).contains(".deleted-"), "列表项应带删除标记：" + m);
            assertTrue(((Number) m.get("size")).longValue() > 0, "应带文件大小");
            assertTrue(m.get("deletedAt") != null, "应带删除时间");
        }
    }

    @Test
    @DisplayName("文件已丢失时删除不报错（记录照删，只记日志）")
    void deleteToleratesMissingFile() throws Exception {
        setMapper(fileInfo(3L, "missing.pdf"));
        service.delete(3L);   // 不应抛异常
        assertTrue(service.trash().isEmpty(), "没有文件就不该在回收站里出现条目");
    }

    // ---------------------------------------------------------------- 辅助

    private FileInfo fileInfo(Long id, String storeName) {
        FileInfo info = new FileInfo();
        info.setId(id);
        info.setStoreName(storeName);
        info.setOriginName(storeName);
        return info;
    }

    /** 让 service.delete(id) 能 require(id) 到这条记录 */
    private void setMapper(FileInfo info) throws Exception {
        Field f = FileStorageService.class.getDeclaredField("fileInfoMapper");
        f.setAccessible(true);
        FileInfoMapper mapper = (FileInfoMapper) f.get(service);
        when(mapper.selectById(info.getId())).thenReturn(info);
    }
}
