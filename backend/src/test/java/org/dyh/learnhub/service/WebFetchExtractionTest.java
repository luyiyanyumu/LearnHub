package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WebFetchExtractionTest {
    @Test void extractsTheActualMainBodyBeforeApplyingTheSourceCharacterCap() {
        String html = "<html><head><style>" + "x".repeat(120_000)
                + "</style></head><body><main><h1>合成正文</h1><p>" + "合成解释".repeat(150) + "</p></main></body></html>";
        var result = WebService.extracted("https://example.invalid/docs/page", 200, "text/html", "html", html);
        assertTrue(result.body().contains("# 合成正文"));
        assertTrue(result.body().contains("合成解释"));
        assertFalse(result.truncated(), "被清除的HTML噪声不能消耗正文字符预算");
    }

    @Test void resolvesRelativeLinksWhileLeavingCodeBlockSourceUntouched() {
        String code = "    path = '/example'\n    marker = '$x$'";
        String html = "<p><a href='../guide?q=1&amp;x=2'>合成指南</a><img src='/image.png'></p>"
                + "<pre><code class='language-python'>" + code + "</code></pre>";
        String result = HtmlToMarkdown.convert(html, "https://example.invalid/docs/page");
        assertTrue(result.contains("[合成指南](https://example.invalid/guide?q=1&x=2)"));
        assertTrue(result.contains("![](https://example.invalid/image.png)"));
        String opening = "```python\n";
        int codeStart = result.indexOf(opening) + opening.length();
        int codeEnd = result.indexOf("\n```", codeStart);
        assertEquals(code, result.substring(codeStart, codeEnd), "HTML包装标签不能给代码首行添加空格");
        assertTrue(HtmlToMarkdown.convert(html).contains("[合成指南](../guide?q=1&x=2)"));
    }

    @Test void sourceLimitIsRealAndDoesNotSplitUtf16Characters() {
        String body = "x".repeat(99999) + "😀" + "tail";
        var result = WebService.extracted("https://example.invalid", 200, "text/plain", "text", body);
        assertTrue(result.truncated());
        assertEquals(99999, result.body().length());
        assertFalse(Character.isHighSurrogate(result.body().charAt(result.body().length() - 1)));
    }

    @Test void dnsWaitCannotOutliveTheWholeFetchBudget() throws Exception {
        WebService web = spy(new WebService(null, null, new ObjectMapper()));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            release.await();
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte)184, (byte)216, 34})};
        }).when(web).lookupAddresses(anyString());
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                var failure = assertThrows(IllegalStateException.class,
                        () -> web.fetch("https://example.invalid/synthetic", 25));
                assertEquals("网页抓取时间预算已用尽", failure.getMessage());
            });
        } finally { release.countDown(); }
    }

    @Test void dynamicEntryValidationUsesTheSameBoundedDnsWait() throws Exception {
        WebService web = spy(new WebService(null, null, new ObjectMapper()));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            release.await();
            return new InetAddress[0];
        }).when(web).lookupAddresses(anyString());
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals("网页抓取时间预算已用尽",
                    assertThrows(IllegalStateException.class,
                            () -> web.validatePublicFetchUrl("https://example.invalid/synthetic", 25)).getMessage()));
        } finally { release.countDown(); }
    }
}
