package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 「把论文存进资料库」的下载层测试：{@link WebService#download}。
 *
 * <p>这是智能体入库能力的**唯一一道闸门**，所以两类断言都要有：
 * <ul>
 *   <li><b>确定性的安全断言</b>（不联网）：非 http(s)、内嵌凭据、指向本机/内网地址都必须拒绝 ——
 *       SSRF 防护要是漏了，模型就能被网页里的一句话骗去读本机的接口；</li>
 *   <li><b>一次真实下载</b>：确认拿到的是 PDF 本体的字节（%PDF 魔数），而不是被 HTML 错误页糊弄。
 *       没网时自动跳过（不把环境问题算成代码失败）。</li>
 * </ul>
 * 这里刻意不依赖 Spring 上下文：{@code download()} 只用到 HTTP 客户端与固定的 User-Agent，
 * 构造时传 null 即可（与仓库里其它纯逻辑测试的取舍一致）。
 */
class WebDownloadTest {

    /** download() 只用 http 客户端与 UA，构造依赖留空是安全的 */
    private final WebService web = new WebService(null, null, new ObjectMapper());

    @Test
    @DisplayName("非 http(s) 协议一律拒绝")
    void rejectsNonHttpScheme() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> web.download("file:///C:/Windows/win.ini"));
        assertTrue(e.getMessage().contains("只允许 http/https"), e.getMessage());
    }

    @Test
    @DisplayName("URL 里内嵌凭据要拒绝（防把凭据带出去）")
    void rejectsEmbeddedCredentials() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> web.download("https://user:pass@example.com/paper.pdf"));
        assertTrue(e.getMessage().contains("不允许内嵌凭据"), e.getMessage());
    }

    @Test
    @DisplayName("★ 指向本机/内网的地址必须拒绝（SSRF）")
    void rejectsPrivateAddress() {
        // 本机回环：就是本工程后端自己的端口，最典型的 SSRF 目标
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> web.download("http://127.0.0.1:18080/api/files"));
        assertTrue(e.getMessage().contains("非公网地址"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> web.download("http://localhost:18080/api/files"));
        // 内网网段与云元数据地址（169.254.169.254）
        assertThrows(IllegalStateException.class, () -> web.download("http://192.168.1.1/paper.pdf"));
        assertThrows(IllegalStateException.class, () -> web.download("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    @DisplayName("真实下载：拿到的是 PDF 字节，不是网页")
    void downloadsRealPdf() {
        WebService.DownloadResult dl;
        try {
            dl = web.download("https://arxiv.org/pdf/1706.03762");
        } catch (IllegalStateException e) {
            // 没网/被墙时跳过：这是环境问题，不是代码问题
            assumeTrue(false, "网络不可用，跳过真实下载断言：" + e.getMessage());
            return;
        }
        assertTrue(dl.bytes() > 100_000, "PDF 太小，可能拿到的是错误页：" + dl.bytes() + " 字节");
        String magic = new String(dl.body(), 0, 5, StandardCharsets.ISO_8859_1);
        assertEquals("%PDF-", magic, "响应体开头不是 PDF 魔数（说明下到的不是 PDF 本体）");
        assertTrue(dl.contentType().contains("pdf"), "Content-Type 不是 pdf：" + dl.contentType());
        // arXiv 的 /pdf/<id> 会跳到带版本号的具体地址，最终地址要如实回报（来源可见）
        assertTrue(dl.url().contains("arxiv.org"), dl.url());
    }
}
