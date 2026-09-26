package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.CodeImportService;
import org.dyh.learnhub.service.CodeLibraryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码库：项目 + 代码片段 + 检索。
 *
 * <p>刻意与 {@code /api/kb/*}（知识检索）分开：代码不进知识库的向量索引，
 * 原因见 {@link CodeLibraryService} 的类注释（信噪比 + 不挤掉个人笔记）。
 * 智能体需要代码时走 {@code search_code} 工具显式查这里。
 */
@RestController
@RequestMapping("/api/code")
@RequiredArgsConstructor
public class CodeController {

    private final CodeLibraryService code;
    private final CodeImportService importer;

    // ---------------- 项目 ----------------

    @GetMapping("/repos")
    public Result<List<Map<String, Object>>> repos() {
        return Result.ok(code.repos());
    }

    @PostMapping("/repos")
    public Result<Map<String, Object>> saveRepo(@RequestBody Map<String, Object> body) {
        return Result.ok(code.saveRepo(null, body));
    }

    @PutMapping("/repos/{id}")
    public Result<Map<String, Object>> updateRepo(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(code.saveRepo(id, body));
    }

    @DeleteMapping("/repos/{id}")
    public Result<Map<String, Object>> deleteRepo(@PathVariable Long id) {
        return Result.ok(code.deleteRepo(id));
    }

    // ---------------- 片段 ----------------

    @GetMapping("/snippets")
    public Result<List<Map<String, Object>>> snippets(@RequestParam(required = false) String lang,
                                                     @RequestParam(required = false) Long repoId,
                                                     @RequestParam(required = false) Boolean unassigned,
                                                     @RequestParam(required = false) Integer limit) {
        return Result.ok(code.list(lang, repoId, Boolean.TRUE.equals(unassigned), limit == null ? 100 : limit));
    }

    @GetMapping("/snippets/{id}")
    public Result<Map<String, Object>> snippet(@PathVariable Long id) {
        return Result.ok(code.detail(id));
    }

    @PostMapping("/snippets")
    public Result<Map<String, Object>> saveSnippet(@RequestBody Map<String, Object> body) {
        return Result.ok(code.saveSnippet(null, body));
    }

    @PutMapping("/snippets/{id}")
    public Result<Map<String, Object>> updateSnippet(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(code.saveSnippet(id, body));
    }

    @DeleteMapping("/snippets/{id}")
    public Result<Object> deleteSnippet(@PathVariable Long id) {
        code.deleteSnippet(id);
        return Result.ok(Map.of("deleted", id));
    }

    // ---------------- 检索 ----------------

    /**
     * @param mode symbol（符号精确/前缀）/ keyword（标题·说明·标识符·正文）/ all（默认，两者合并）
     */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestParam String q,
                                                    @RequestParam(required = false) String mode,
                                                    @RequestParam(required = false) String lang,
                                                    @RequestParam(required = false) Long repoId,
                                                    @RequestParam(required = false) Boolean unassigned,
                                                    @RequestParam(required = false) Integer limit) {
        return Result.ok(code.search(q, mode, lang, repoId, Boolean.TRUE.equals(unassigned), limit == null ? 50 : limit));
    }

// ---------------- 文件夹导入 ----------------

    /**
     * 导入一个文件夹：前端读文本 + 相对路径，后端按语言识别并过滤噪声，逐文件入库建索引。
     *
     * <p>为什么用 JSON 而不是 multipart：① 只需要文本，不需要保留二进制；
     * ② 省掉 multipart 大小限制/临时文件的坑；③ 过滤规则与"跳过原因"能整包一起返回，
     * 界面可以如实告诉用户"进来了多少、跳过了什么"。
     */
    @PostMapping("/import-folder")
    public Result<Map<String, Object>> importFolder(@RequestBody Map<String, Object> body) {
        String projectName = body.get("projectName") == null ? null : String.valueOf(body.get("projectName"));
        Long repoId = body.get("repoId") == null || String.valueOf(body.get("repoId")).isBlank()
                ? null : Long.valueOf(String.valueOf(body.get("repoId")));
        Object filesObj = body.get("files");
        if (!(filesObj instanceof List<?> raw)) {
            return Result.error(400, "files 必须是数组");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) raw;
        CodeImportService.Imported r = importer.importFolder(projectName, repoId, files);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", r.created());
        out.put("updated", r.updated());
        out.put("skipped", r.skipped());
        out.put("bytes", r.bytes());
        out.put("langs", r.langs());
        out.put("skipReasons", r.skipReasons());
        out.put("skipSamples", r.skipSamples());
        return Result.ok(out);
    }

    /** 前端预筛用：后端认得哪些扩展名（单一源头，避免前端维护第二份名单） */
    @GetMapping("/extensions")
    public Result<List<String>> extensions() {
        return Result.ok(new ArrayList<>(CodeImportService.knownExtensions()));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(code.stats());
    }
}
