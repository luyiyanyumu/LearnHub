package org.dyh.learnhub.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「把论文存进资料库」的三道守卫（纯函数，不联网、不碰数据库）。
 *
 * <p>为什么值得单独立测试：这三处一旦写错，**不会报错**，只会安静地存进一份
 * "有记录、抽不出正文、检索里查不到"的资料 —— 用户以为存好了，其实用不上。
 * 真实踩过的两个坑就写在下面对应的用例里。
 */
class AgentFileImportGuardTest {

    @Test
    @DisplayName("★ arXiv 的 PDF 直链没有 .pdf 后缀：要按 Content-Type 补出可抽取的后缀")
    void arxivPdfLinkGetsRealExtension() {
        // 实测坑：/pdf/1706.03762 的"后缀"是 ".03762"，后端会判 unsupported，整份论文白传
        assertEquals("1706.03762.pdf",
                AgentService.guessFileName("https://arxiv.org/pdf/1706.03762", "application/pdf"));
        // 已经带对后缀的地址原样保留
        assertEquals("attention.pdf",
                AgentService.guessFileName("https://example.com/papers/attention.pdf", "application/pdf"));
        // 类型也给不出可抽取后缀时返回空串 → 上层拒收，而不是硬存一个 "xxx.03762"
        assertEquals("", AgentService.guessFileName("https://example.com/data/2024.0001", "application/zip"));
        // 文件名为空时给一个占位名 + 真后缀
        assertEquals("download.pdf", AgentService.guessFileName("https://example.com/", "application/pdf"));
    }

    @Test
    @DisplayName("★ 落地页要能认出来（Content-Type 说是 HTML，或响应体开头是 HTML）")
    void detectsLandingPages() {
        byte[] html = "<!DOCTYPE html>\n<html><head><title>arXiv</title></head>".getBytes(StandardCharsets.UTF_8);
        assertTrue(AgentService.looksLikeHtml(html));
        assertTrue(AgentService.looksLikeHtml("<html lang=\"en\">".getBytes(StandardCharsets.UTF_8)));
        byte[] pdf = "%PDF-1.7\n%âãÏÓ".getBytes(StandardCharsets.ISO_8859_1);
        assertFalse(AgentService.looksLikeHtml(pdf));
        assertFalse(AgentService.looksLikeHtml(new byte[0]));
        assertFalse(AgentService.looksLikeHtml(null));
    }

    @Test
    @DisplayName("文件名里的危险字符要清掉（不能带出路径分隔符）")
    void sanitizesFileName() {
        String name = AgentService.guessFileName("https://example.com/a%20b/c:d|e.pdf", "application/pdf");
        assertFalse(name.contains(":"), name);
        assertFalse(name.contains("|"), name);
        assertTrue(name.endsWith(".pdf"), name);
    }
}
