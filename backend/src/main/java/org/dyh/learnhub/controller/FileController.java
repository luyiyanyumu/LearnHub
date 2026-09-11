package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.FileStorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /** 资料列表 */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) Long categoryId,
                                                  @RequestParam(required = false) String kw) {
        return Result.ok(fileStorageService.list(categoryId, kw));
    }

    /** 上传资料 */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) Long categoryId) {
        return Result.ok(fileStorageService.toMap(fileStorageService.upload(file, categoryId)));
    }

    /** 下载资料 */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        FileStorageService.DownloadItem item = fileStorageService.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + item.encodedName())
                .body(item.resource());
    }

    /** 删除资料 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        fileStorageService.delete(id);
        return Result.ok();
    }
}
