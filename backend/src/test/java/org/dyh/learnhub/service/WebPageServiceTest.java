package org.dyh.learnhub.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Immutable synthetic pages and a fake clock; no network, filesystem or database. */
class WebPageServiceTest {
    private static final String URL = "https://example.invalid/document";
    private final WebService web = mock(WebService.class);
    private final ScraplingClient browser = mock(ScraplingClient.class);
    private final AtomicLong clock = new AtomicLong();
    private WebPageService pages;

    @BeforeEach void setup() { pages = new WebPageService(web, browser, clock::get); }

    @Test void pagesReassembleTheSameSnapshotAndContinuationNeverUsesTheNetwork() {
        String body = "合成网页正文".repeat(4000);
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", body, false));
        var first = pages.read("s1", URL, null, 0, 8000, null, 30_000);
        assertFalse(first.cached());
        assertFalse(first.sourceTruncated());
        assertEquals(0, first.offset());
        assertEquals(8000, first.nextOffset());
        assertEquals(body.length(), first.totalChars());
        StringBuilder actual = new StringBuilder(first.content());
        var part = first;
        while (part.hasMore()) {
            part = pages.read("s1", null, first.pageId(), part.nextOffset(), 8000, "auto", 0);
            assertTrue(part.cached());
            assertEquals(first.pageId(), part.pageId());
            assertFalse(part.sourceTruncated(), "分页不能冒充抓取源截断");
            actual.append(part.content());
        }
        assertEquals(-1, part.nextOffset());
        assertEquals(body, actual.toString());
        verify(web).fetch(URL, 30_000);
        verifyNoMoreInteractions(web);
        verifyNoInteractions(browser);
    }

    @Test void sourceTruncationIsIndependentOfPaginationAndEndOffsetIsExplicit() {
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", "真实但受源上限的合成正文", true));
        var first = pages.read("s1", URL, null, 0, 8000, "http", 30_000);
        assertTrue(first.sourceTruncated());
        assertFalse(first.hasMore());
        assertEquals(-1, first.nextOffset());
        var empty = pages.read("s1", null, first.pageId(), first.totalChars(), 8000, "http", 0);
        assertEquals("", empty.content());
        assertEquals(-1, empty.nextOffset());
        assertFalse(empty.hasMore());
        assertTrue(empty.sourceTruncated());
    }

    @Test void unknownExpiredAndOtherSessionSnapshotsHaveTheSameErrorAndNeverRefetch() {
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", "合成正文", false));
        var first = pages.read("s1", URL, null, 0, 3, "http", 30_000);
        String unknown = assertThrows(IllegalArgumentException.class,
                () -> pages.read("s1", null, "unknown", 0, 3, "http", 30_000)).getMessage();
        String foreign = assertThrows(IllegalArgumentException.class,
                () -> pages.read("s2", null, first.pageId(), 0, 3, "http", 30_000)).getMessage();
        clock.set(WebPageService.CACHE_TTL_MS);
        String expired = assertThrows(IllegalArgumentException.class,
                () -> pages.read("s1", URL, first.pageId(), 0, 3, "http", 30_000)).getMessage();
        assertEquals(unknown, foreign);
        assertEquals(unknown, expired);
        verify(web).fetch(anyString(), anyLong());
    }

    @Test void lruEvictsTheLeastRecentlyReadEntryAtTheCountLimit() {
        when(web.fetch(anyString(), anyLong())).thenAnswer(call ->
                new WebService.FetchResult(call.getArgument(0), 200, "text/plain", "合成", false));
        var first = pages.read("s", URL + "/0", null, 0, 1, "http", 30_000);
        var second = pages.read("s", URL + "/1", null, 0, 1, "http", 30_000);
        for (int i = 2; i < WebPageService.MAX_ENTRIES; i++)
            pages.read("s", URL + "/" + i, null, 0, 1, "http", 30_000);
        pages.read("s", null, first.pageId(), 0, 1, "http", 0);
        pages.read("s", URL + "/new", null, 0, 1, "http", 30_000);
        assertThrows(IllegalArgumentException.class, () -> pages.read("s", null, second.pageId(), 0, 1, "http", 0));
        assertTrue(pages.read("s", null, first.pageId(), 0, 1, "http", 0).cached());
    }

    @Test void totalCharacterBudgetEvictsOldSnapshotsBeforeTheEntryCountLimit() {
        String body = "合".repeat(100_000);
        when(web.fetch(anyString(), anyLong())).thenAnswer(call ->
                new WebService.FetchResult(call.getArgument(0), 200, "text/plain", body, false));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 21; i++)
            ids.add(pages.read("s", URL + "/" + i, null, 0, 8000, "http", 30_000).pageId());
        assertThrows(IllegalArgumentException.class, () -> pages.read("s", null, ids.getFirst(), 0, 8000, "http", 0));
        assertTrue(pages.read("s", null, ids.get(1), 0, 8000, "http", 0).cached());
    }

    @Test void offsetWithoutSnapshotAndInvalidParametersNeverFetch() {
        assertThrows(IllegalArgumentException.class, () -> pages.read("s", URL, null, 1, 8000, "auto", 30_000));
        assertThrows(IllegalArgumentException.class, () -> pages.read("s", URL, null, -1, 8000, "auto", 30_000));
        assertThrows(IllegalArgumentException.class, () -> pages.read("s", URL, null, 0, 0, "auto", 30_000));
        assertThrows(IllegalArgumentException.class, () -> pages.read("s", URL, null, 0, 12001, "auto", 30_000));
        assertThrows(IllegalArgumentException.class, () -> pages.read("s", URL, null, 0, 8000, "unsafe", 30_000));
        assertThrows(IllegalArgumentException.class, () -> pages.read("", URL, null, 0, 8000, "auto", 30_000));
        assertThrows(IllegalStateException.class, () -> pages.read("s", URL, null, 0, 8000, "auto", 0));
        verifyNoInteractions(web, browser);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    void autoFallsBackOnlyForActualHttpBlockingResponses(int status) {
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(status, "text/html", "合成受限页面", false));
        when(browser.configured()).thenReturn(true);
        when(browser.fetch(eq(URL), anyLong())).thenReturn(rendered("动态合成正文"));
        var result = pages.read("s", URL, null, 0, 8000, "auto", 30_000);
        assertEquals("dynamic", result.fetchMode());
        assertEquals(200, result.status());
        assertEquals("动态合成正文", result.content());
        assertEquals("动态合成标题", result.title());
        verify(web).fetch(URL, 30_000);
        verify(browser).fetch(URL, 30_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Please enable JavaScript to view this page.", "Loading...", "Loading course catalog...", "加载中…"})
    void anEmptyHtmlOrObviousJavascriptShellCanUseTheRenderer(String shell) {
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/html; charset=utf-8", shell, false));
        when(browser.configured()).thenReturn(true);
        when(browser.fetch(eq(URL), anyLong())).thenReturn(rendered("真实动态合成正文"));
        assertEquals("dynamic", pages.read("s", URL, null, 0, 8000, "auto", 30_000).fetchMode());
    }

    @Test void a404NeverTriggersBrowserEvenWhenItsBodyMentionsJavascript() {
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(404, "text/html", "Please enable JavaScript", false));
        var result = pages.read("s", URL, null, 0, 8000, "auto", 30_000);
        assertEquals(404, result.status());
        assertEquals("http", result.fetchMode());
        verifyNoInteractions(browser);
    }

    @Test void normalHtmlAndExplicitHttpModeDoNotUseBrowser() {
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/html", "完整可读的合成网页正文", false));
        assertEquals("http", pages.read("s", URL, null, 0, 8000, "auto", 30_000).fetchMode());
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(403, "text/html", "合成受限正文", false));
        assertEquals(403, pages.read("s", URL, null, 0, 8000, "http", 30_000).status());
        verifyNoInteractions(browser);
    }

    @Test void unavailableAutoRendererRetainsActualHttpBodyAndSafeWarning() {
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(403, "text/html", "原HTTP合成正文", false));
        when(browser.configured()).thenReturn(true);
        when(browser.fetch(eq(URL), anyLong())).thenThrow(new IllegalStateException("synthetic-secret"));
        var result = pages.read("s", URL, null, 0, 8000, "auto", 30_000);
        assertEquals(403, result.status());
        assertEquals("http", result.fetchMode());
        assertEquals("原HTTP合成正文", result.content());
        assertFalse(result.warning().isBlank());
        assertFalse(result.warning().contains("synthetic-secret"));
    }

    @Test void explicitDynamicRequiresConfigurationAndKeepsExistingPublicUrlValidation() {
        assertEquals("动态网页服务未配置", assertThrows(IllegalStateException.class,
                () -> pages.read("s", URL, null, 0, 8000, "dynamic", 30_000)).getMessage());
        verifyNoInteractions(web);
        when(browser.configured()).thenReturn(true);
        doThrow(new IllegalStateException("拒绝访问非公网地址")).when(web).validatePublicFetchUrl(eq(URL), anyLong());
        assertThrows(IllegalStateException.class, () -> pages.read("s", URL, null, 0, 8000, "dynamic", 30_000));
        verify(browser, never()).fetch(anyString(), anyLong());
    }

    @Test void httpValidationFailureNeverFallsBackToBrowser() {
        when(web.fetch(eq(URL), anyLong())).thenThrow(new IllegalStateException("拒绝访问非公网地址"));
        assertThrows(IllegalStateException.class, () -> pages.read("s", URL, null, 0, 8000, "auto", 30_000));
        verifyNoInteractions(browser);
    }

    @Test void httpAndBrowserShareOneTimeBudget() {
        when(web.fetch(eq(URL), anyLong())).thenAnswer(call -> {
            assertEquals(10_000L, call.getArgument(1, Long.class).longValue());
            clock.set(2_000);
            return http(403, "text/html", "原HTTP正文", false);
        });
        when(browser.configured()).thenReturn(true);
        when(browser.fetch(URL, 8_000)).thenReturn(rendered("动态合成正文"));
        assertEquals("dynamic", pages.read("s", URL, null, 0, 8000, "auto", 10_000).fetchMode());
        verify(browser).fetch(URL, 8_000);
    }

    @Test void exhaustedHttpBudgetDoesNotResetTheBrowserBudget() {
        when(web.fetch(eq(URL), anyLong())).thenAnswer(call -> {
            clock.set(10_000);
            return http(403, "text/html", "真实HTTP正文", false);
        });
        when(browser.configured()).thenReturn(true);
        var result = pages.read("s", URL, null, 0, 8000, "auto", 10_000);
        assertEquals("http", result.fetchMode());
        assertFalse(result.warning().isBlank());
        verify(browser, never()).fetch(anyString(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$$\na=b+c\nc=d\n$$\n", "\\[\na=b+c\nc=d\n\\]\n",
            "\\begin{aligned}\na&=b+c\\\\\nc&=d\n\\end{aligned}\n",
            "> ~~~python\n> x = '$$'\n> print(x)\n> ~~~\n",
            "~~~python\nx = '$$'\nprint(x)\n~~~\n"})
    void formulasAndFencedCodeAreReturnedAtomicallyAndWithoutRewriting(String atom) {
        String body = "合成前文\n" + atom + "合成后文";
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", body, false));
        var first = pages.read("s", URL, null, 0, 10, "http", 30_000);
        assertEquals("合成前文\n", first.content());
        var second = pages.read("s", null, first.pageId(), first.nextOffset(), 5, "http", 0);
        assertEquals(atom, second.content());
        assertTrue(second.content().length() > 5);
        var last = pages.read("s", null, first.pageId(), second.nextOffset(), 8000, "http", 0);
        assertEquals(body, first.content() + second.content() + last.content());
        assertThrows(IllegalArgumentException.class,
                () -> pages.read("s", null, first.pageId(), first.nextOffset() + 1, 8000, "http", 0));
    }

    @Test void anOversizedAtomicBlockFailsExplicitlyInsteadOfSkippingOrTruncatingIt() {
        String body = "~~~\n" + "x".repeat(12000) + "\n~~~";
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", body, false));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> pages.read("s", URL, null, 0, 8000, "http", 30_000));
        assertTrue(failure.getMessage().contains("12000"));
    }

    @Test void utf16OffsetsNeverSplitAnEmojiAndInvalidMiddleOffsetsAreRejected() {
        String body = "a😀b";
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", body, false));
        var first = pages.read("s", URL, null, 0, 2, "http", 30_000);
        assertEquals("a", first.content());
        assertEquals(1, first.nextOffset());
        var second = pages.read("s", null, first.pageId(), 1, 1, "http", 0);
        assertEquals("😀", second.content());
        assertEquals(3, second.nextOffset());
        assertThrows(IllegalArgumentException.class, () -> pages.read("s", null, first.pageId(), 2, 1, "http", 0));
        var last = pages.read("s", null, first.pageId(), 3, 1, "http", 0);
        assertEquals(body, first.content() + second.content() + last.content());
    }

    @Test void formulaDelimitersInsideInlineCodeCannotTurnTheRestOfThePageIntoOneFormula() {
        String body = "Use `$$` and `\\[` in the synthetic example.\n" + "x".repeat(13000);
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", body, false));
        var first = pages.read("s", URL, null, 0, 8000, "http", 30_000);
        var last = pages.read("s", null, first.pageId(), first.nextOffset(), 8000, "http", 0);
        assertEquals(body, first.content() + last.content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$$x+y=z$$", "\\[x+y=z\\]", "\\(x+y=z\\)", "$x+y=z$",
            "`code + value`", "``nested ` tick``"})
    void inlineMathAndCodeSpansStayWholeAtSmallPageBoundaries(String atom) {
        String body = "before " + atom + " after";
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", body, false));
        var first = pages.read("s", URL, null, 0, 10, "http", 30_000);
        assertEquals("before ", first.content());
        var second = pages.read("s", null, first.pageId(), first.nextOffset(), 2, "http", 0);
        assertEquals(atom, second.content());
        var last = pages.read("s", null, first.pageId(), second.nextOffset(), 8000, "http", 0);
        assertEquals(body, first.content() + second.content() + last.content());
    }

    @Test void genuineShortContentThatDiscussesLoadingDoesNotTriggerDynamicRendering() {
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/html", "Loading course catalogs uses a paginated API.", false));
        assertEquals("http", pages.read("s", URL, null, 0, 8000, "auto", 30_000).fetchMode());
        verifyNoInteractions(browser);
    }

    @Test void aClosedFormulaBeforeAMultilineFormulaOnTheSameLineDoesNotHideItsOpening() {
        String atom = "$$a$$ then $$\nx+y=z\n$$\n";
        String body = "before " + atom + "after";
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", body, false));
        var first = pages.read("s", URL, null, 0, 20, "http", 30_000);
        assertEquals("before $$a$$ then ", first.content());
        var second = pages.read("s", null, first.pageId(), first.nextOffset(), 2, "http", 0);
        assertEquals("$$\nx+y=z\n$$\n", second.content());
        var last = pages.read("s", null, first.pageId(), second.nextOffset(), 8000, "http", 0);
        assertEquals(body, first.content() + second.content() + last.content());
    }

    @Test void aSingleLineBareLatexEnvironmentIsAlsoAtomic() {
        String atom = "\\begin{aligned}x+y=z\\end{aligned}";
        String body = "before " + atom + " after";
        when(web.fetch(eq(URL), anyLong())).thenReturn(http(200, "text/plain", body, false));
        var first = pages.read("s", URL, null, 0, 10, "http", 30_000);
        assertEquals("before ", first.content());
        var second = pages.read("s", null, first.pageId(), first.nextOffset(), 2, "http", 0);
        assertEquals(atom, second.content());
    }

    private static WebService.FetchResult http(int status, String type, String body, boolean truncated) {
        return new WebService.FetchResult(URL, status, type, body, truncated);
    }

    private static ScraplingClient.Result rendered(String body) {
        return new ScraplingClient.Result(URL, 200, "text/html", "动态合成标题", body, false, "dynamic");
    }
}
