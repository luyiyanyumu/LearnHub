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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final org.dyh.learnhub.service.TranslationService translationService;

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

    /** 资料详情（含分类名、是否有正文、抽取状态） */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(fileStorageService.detailMap(id));
    }

    /** 更新手填说明 —— 抽不出正文的资料靠它进检索 */
    @PutMapping("/{id}/summary")
    public Result<Map<String, Object>> updateSummary(@PathVariable Long id,
                                                     @RequestBody Map<String, String> body) {
        return Result.ok(fileStorageService.updateSummary(id, body.get("summary")));
    }

    /** 换分类（资料按分类进知识图谱） */
    @PutMapping("/{id}/category")
    public Result<Map<String, Object>> updateCategory(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> body) {
        Object raw = body.get("categoryId");
        Long categoryId = raw == null || "".equals(raw) || "0".equals(String.valueOf(raw))
                ? null : Long.valueOf(String.valueOf(raw));
        return Result.ok(fileStorageService.updateCategory(id, categoryId));
    }

    /** 手动重新抽取正文 */
    @PostMapping("/{id}/reextract")
    public Result<Map<String, Object>> reextract(@PathVariable Long id) {
        return Result.ok(fileStorageService.reextract(id));
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

    /**
     * **内联打开**原文（在线阅读）。
     *
     * <p>与 download 的区别就在 Content-Disposition：这里是 {@code inline} +
     * 按扩展名给正确的 Content-Type，于是 PDF 会交给浏览器自带的阅读器渲染、
     * 图片/文本/音视频直接在页面上显示，而不是弹一个下载框。
     * 资料库的"打开阅读"用它做画面，抽取出来的正文另走 detail 接口。
     */
    @GetMapping("/{id}/raw")
    public ResponseEntity<Resource> raw(@PathVariable Long id) {
        FileStorageService.DownloadItem item = fileStorageService.download(id);
        return ResponseEntity.ok()
                .contentType(item.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + item.encodedName())
                // 内联展示的是用户自己上传的资料；禁止被当成脚本执行（防 XSS）
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; media-src 'self'; object-src 'self'; style-src 'unsafe-inline'")
                .body(item.resource());
    }

/**
     * 取**抽取出来的正文**（在线阅读用）。
     *
     * <p>为什么不塞进 detail：正文动辄十几万字（实测一份 PDF 118,747 字），
     * 列表/详情页根本用不到，塞进去等于每次点开都传一遍全文。
     * 单独一个接口，只有真正打开阅读时才拉。
     */
    @GetMapping("/{id}/text")
    public Result<Map<String, Object>> text(@PathVariable Long id) {
        return Result.ok(fileStorageService.textOf(id));
    }
/** 翻译能力（界面用它显示"用哪个档案翻、单段上限多少"） */
    @GetMapping("/translate/capabilities")
    public Result<Map<String, Object>> translateCaps() {
        return Result.ok(translationService.capabilities());
    }

    /**
     * 分段翻译（阅读器用）。**只接受一段**：全文翻译会撞上输出上限被截断，
     * 而且读到哪里翻到哪里更快、也能立刻判断质量。
     */
    @PostMapping("/{id}/translate")
    public Result<Map<String, Object>> translate(@PathVariable Long id, @RequestBody Map<String, String> body) {
        fileStorageService.detail(id); // 校验资料存在（顺带让日志里能追到是哪份资料）
        return Result.ok(translationService.translate(body.get("text"), body.get("targetLang")));
    }
/** 保存阅读位置：翻页/缩放时由阅读器防抖调用 */
    @PutMapping("/{id}/reading-state")
    public Result<Map<String, Object>> saveReadingState(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer page = body.get("page") == null || String.valueOf(body.get("page")).isBlank()
                ? null : Integer.valueOf(String.valueOf(body.get("page")));
        java.math.BigDecimal scale = body.get("scale") == null || String.valueOf(body.get("scale")).isBlank()
                ? null : new java.math.BigDecimal(String.valueOf(body.get("scale")));
        String mode = body.get("mode") == null ? null : String.valueOf(body.get("mode"));
        return Result.ok(fileStorageService.saveReadingState(id, page, scale, mode));
    }
    /** 删除资料 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        fileStorageService.delete(id);
        return Result.ok();
    }
}
