package org.dyh.learnhub.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Session-owned immutable page snapshots. Follow-up reads never fetch a different version of the page. */
@Service
public class WebPageService {
    static final long CACHE_TTL_MS = 15 * 60_000L;
    static final int MAX_ENTRIES = 64;
    static final int MAX_CACHE_CHARS = 2_000_000;
    static final int MAX_PAGE_CHARS = 12_000;
    private static final String MISSING_PAGE = "网页快照不存在或已过期，请从 offset=0 重新抓取";
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern ENVIRONMENT = Pattern.compile("\\\\(begin|end)\\{([A-Za-z*]+)}");
    private final WebService web;
    private final ScraplingClient scrapling;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Snapshot> cache = new LinkedHashMap<>(16, 0.75f, true);
    private int cachedChars;

    public record PageRead(String pageId, String url, int status, String contentType, String fetchMode,
                           String title, String content, int offset, int nextOffset, int totalChars,
                           boolean hasMore, boolean sourceTruncated, boolean cached, String warning) {}
    private record Atom(int start, int end) {}
    private record Snapshot(String id, String sessionId, String url, int status, String contentType,
                            String fetchMode, String title, String body, boolean truncated, String warning,
                            long createdAt, List<Atom> atoms) {}

    @Autowired
    public WebPageService(WebService web, ScraplingClient scrapling) {
        this(web, scrapling, System::currentTimeMillis);
    }

    WebPageService(WebService web, ScraplingClient scrapling, LongSupplier clock) {
        this.web = web;
        this.scrapling = scrapling;
        this.clock = clock;
    }

    public PageRead read(String sessionId, String url, String pageId, int offset, int maxChars, String mode, long timeoutMs) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("网页续读需要有效会话");
        if (offset < 0) throw new IllegalArgumentException("offset 不能小于0");
        if (maxChars < 1 || maxChars > MAX_PAGE_CHARS) throw new IllegalArgumentException("maxChars 必须在1到12000之间");
        String selected = mode == null || mode.isBlank() ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "http", "dynamic").contains(selected)) throw new IllegalArgumentException("mode 必须是auto、http或dynamic");
        if (pageId != null && !pageId.isBlank()) {
            Snapshot snapshot = lookup(sessionId, pageId.trim());
            if (url != null && !url.isBlank() && !url.trim().equals(snapshot.url()))
                throw new IllegalArgumentException("续读URL与网页快照不一致，请使用原page_id或重新抓取");
            return page(snapshot, offset, maxChars, true);
        }
        if (offset != 0) throw new IllegalArgumentException("offset>0 必须提供仍有效的page_id，不能重新抓取拼接版本");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("缺少网页URL");
        if (timeoutMs <= 0) throw new IllegalStateException("网页抓取时间预算已用尽");
        long started = clock.getAsLong();
        long budget = Math.min(timeoutMs, 120_000);
        Snapshot snapshot;
        if ("dynamic".equals(selected)) {
            if (!scrapling.configured()) throw new IllegalStateException("动态网页服务未配置");
            web.validatePublicFetchUrl(url.trim(), remaining(started, budget));
            ScraplingClient.Result result = scrapling.fetch(url.trim(), remaining(started, budget));
            snapshot = dynamic(sessionId, result);
        } else {
            WebService.FetchResult result = web.fetch(url.trim(), remaining(started, budget));
            snapshot = http(sessionId, result, "");
            if ("auto".equals(selected) && needsDynamic(result)) {
                if (!scrapling.configured()) snapshot = http(sessionId, result, "动态网页服务未配置，已保留实际HTTP结果");
                else {
                    try {
                        ScraplingClient.Result rendered = scrapling.fetch(result.url(), remaining(started, budget));
                        snapshot = dynamic(sessionId, rendered);
                    } catch (IllegalStateException unavailable) {
                        snapshot = http(sessionId, result, "动态网页抓取未完成，已保留实际HTTP结果；可稍后重试dynamic模式");
                    }
                }
            }
        }
        PageRead first = page(snapshot, 0, maxChars, false);
        remember(snapshot);
        return first;
    }

    private long remaining(long started, long budget) {
        long left = budget - Math.max(0, clock.getAsLong() - started);
        if (left <= 0) throw new IllegalStateException("网页抓取时间预算已用尽");
        return left;
    }

    private Snapshot http(String sessionId, WebService.FetchResult result, String warning) {
        String body = result.body() == null ? "" : result.body();
        return new Snapshot(UUID.randomUUID().toString(), sessionId, result.url(), result.status(),
                result.contentType() == null ? "" : result.contentType(), "http", "", body, result.truncated(),
                warning, clock.getAsLong(), atoms(body));
    }

    private Snapshot dynamic(String sessionId, ScraplingClient.Result result) {
        String body = result.markdown() == null ? "" : result.markdown();
        return new Snapshot(UUID.randomUUID().toString(), sessionId, result.url(), result.status(), result.contentType(),
                "dynamic", result.title(), body, result.truncated(), "", clock.getAsLong(), atoms(body));
    }

    private static boolean needsDynamic(WebService.FetchResult result) {
        if (result.status() == 403 || result.status() == 429) return true;
        if (result.status() < 200 || result.status() >= 300) return false;
        String type = result.contentType() == null ? "" : result.contentType().toLowerCase(Locale.ROOT);
        if (!type.contains("html")) return false;
        String text = result.body() == null ? "" : result.body().strip();
        if (text.isEmpty()) return true;
        if (text.length() > 1500) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("enable javascript") || lower.contains("javascript is required")
                || lower.contains("javascript required") || lower.contains("please turn javascript on")
                || text.contains("请启用JavaScript") || text.contains("请启用 JavaScript")
                || text.contains("需要启用JavaScript") || lower.matches("(?s)^loading(?:[!\\s]*|(?:\\.{2,}|…+)[!\\s]*)$")
                || (text.length() <= 200 && (lower.matches("(?s)^loading(?:\\s+[^\\r\\n.!?]{1,120})?(?:\\.{2,}|…+)\\s*$")
                    || text.matches("^(?:加载中|正在加载|页面加载中|数据加载中)[.…!\\s]*$")));
    }

    private synchronized Snapshot lookup(String sessionId, String id) {
        expire();
        Snapshot snapshot = cache.get(id);
        if (snapshot == null || !snapshot.sessionId().equals(sessionId)) throw new IllegalArgumentException(MISSING_PAGE);
        return snapshot;
    }

    private synchronized void remember(Snapshot snapshot) {
        expire();
        if (snapshot.body().length() > MAX_CACHE_CHARS) throw new IllegalStateException("网页正文超过缓存安全上限");
        while (!cache.isEmpty() && (cache.size() >= MAX_ENTRIES || cachedChars + snapshot.body().length() > MAX_CACHE_CHARS)) {
            Iterator<Snapshot> oldest = cache.values().iterator();
            cachedChars -= oldest.next().body().length();
            oldest.remove();
        }
        cache.put(snapshot.id(), snapshot);
        cachedChars += snapshot.body().length();
    }

    private void expire() {
        long now = clock.getAsLong();
        Iterator<Snapshot> entries = cache.values().iterator();
        while (entries.hasNext()) {
            Snapshot snapshot = entries.next();
            if (now - snapshot.createdAt() >= CACHE_TTL_MS) {
                cachedChars -= snapshot.body().length();
                entries.remove();
            }
        }
    }

    private static PageRead page(Snapshot snapshot, int offset, int requested, boolean cached) {
        String body = snapshot.body();
        if (offset > body.length()) throw new IllegalArgumentException("offset 超过网页正文长度");
        if (bisectsSurrogate(body, offset)) throw new IllegalArgumentException("offset 位于Unicode字符内部，请使用next_offset");
        for (Atom atom : snapshot.atoms())
            if (atom.start() < offset && offset < atom.end())
                throw new IllegalArgumentException("offset 位于公式或代码块内部，请使用next_offset");
        int end = Math.min(body.length(), offset + requested);
        if (bisectsSurrogate(body, end)) end--;
        if (end == offset && offset < body.length()) end = Math.min(body.length(), offset + 2);
        for (Atom atom : snapshot.atoms()) {
            if (atom.start() < end && end < atom.end()) {
                if (atom.start() > offset) end = atom.start();
                else {
                    if (atom.end() - offset > MAX_PAGE_CHARS)
                        throw new IllegalArgumentException("单个公式或代码块超过12000字符，无法在保留完整结构的前提下分页");
                    end = atom.end();
                }
                break;
            }
        }
        boolean more = end < body.length();
        return new PageRead(snapshot.id(), snapshot.url(), snapshot.status(), snapshot.contentType(), snapshot.fetchMode(),
                snapshot.title(), body.substring(offset, end), offset, more ? end : -1, body.length(),
                more, snapshot.truncated(), cached, snapshot.warning());
    }

    private static boolean bisectsSurrogate(String text, int at) {
        return at > 0 && at < text.length() && Character.isHighSurrogate(text.charAt(at - 1))
                && Character.isLowSurrogate(text.charAt(at));
    }

    /** Exact source ranges: no delimiter repair, trimming, code rewriting, or omitted characters. */
    private static List<Atom> atoms(String text) {
        List<Atom> fences = new ArrayList<>();
        String fence = null;
        int fenceStart = -1;
        for (int at = 0; at < text.length();) {
            int newline = text.indexOf('\n', at);
            int end = newline < 0 ? text.length() : newline + 1;
            String line = text.substring(at, end).replaceFirst("\\r?\\n$", "")
                    .replaceFirst("^(?: {0,3}>[ \\t]?)+", "");
            Matcher marker = FENCE.matcher(line);
            if (fence == null && marker.matches()) {
                fence = marker.group(1);
                fenceStart = at;
            } else if (fence != null && marker.matches() && marker.group(1).charAt(0) == fence.charAt(0)
                    && marker.group(1).length() >= fence.length() && marker.group(2).isBlank()) {
                fences.add(new Atom(fenceStart, end));
                fence = null;
            }
            at = end;
        }
        if (fence != null) fences.add(new Atom(fenceStart, text.length()));
        List<Atom> all = new ArrayList<>();
        int from = 0;
        for (Atom block : fences) {
            scanMathAndInlineCode(text.substring(from, block.start()), from, all);
            all.add(block);
            from = block.end();
        }
        scanMathAndInlineCode(text.substring(from), from, all);
        all.sort(Comparator.comparingInt(Atom::start));
        return List.copyOf(all);
    }

    private static void scanMathAndInlineCode(String text, int base, List<Atom> atoms) {
        for (int at = 0; at < text.length();) {
            if (text.charAt(at) == 96) {
                int after = at + 1;
                while (after < text.length() && text.charAt(after) == 96) after++;
                String marker = text.substring(at, after);
                int close = matchingTicks(text, marker, after);
                if (close >= 0) {
                    int end = close + marker.length();
                    atoms.add(new Atom(base + at, base + end));
                    at = end;
                } else at = after;
                continue;
            }
            Matcher environment = ENVIRONMENT.matcher(text).region(at, text.length());
            if (environment.lookingAt() && "begin".equals(environment.group(1))
                    && unescapedIndex(text, "\\begin{", at) == at) {
                String name = environment.group(2);
                int depth = 1, end = text.length();
                while (environment.find()) {
                    if (!name.equals(environment.group(2))) continue;
                    depth += "begin".equals(environment.group(1)) ? 1 : -1;
                    if (depth == 0) { end = environment.end(); break; }
                }
                end = includeLineBreak(text, end);
                atoms.add(new Atom(base + at, base + end));
                at = end;
                continue;
            }
            String opening = null, closing = null;
            if (text.charAt(at) == '$' && unescapedIndex(text, "$", at) == at) {
                opening = text.startsWith("$$", at) ? "$$" : "$";
                closing = opening;
            } else if (text.startsWith("\\[", at) && unescapedIndex(text, "\\[", at) == at) {
                opening = "\\["; closing = "\\]";
            } else if (text.startsWith("\\(", at) && unescapedIndex(text, "\\(", at) == at) {
                opening = "\\("; closing = "\\)";
            }
            if (opening != null) {
                int close = unescapedIndex(text, closing, at + opening.length());
                if ("$".equals(opening)) {
                    int newline = text.indexOf('\n', at);
                    if (close < 0 || (newline >= 0 && close > newline)) { at++; continue; }
                }
                int end = close < 0 ? text.length() : close + closing.length();
                if (!"$".equals(opening)) end = includeLineBreak(text, end);
                atoms.add(new Atom(base + at, base + end));
                at = end;
                continue;
            }
            at++;
        }
    }

    private static int includeLineBreak(String text, int end) {
        if (end < text.length() && text.charAt(end) == '\r') end++;
        if (end < text.length() && text.charAt(end) == '\n') end++;
        return end;
    }

    private static int unescapedIndex(String text, String marker, int from) {
        for (int at = text.indexOf(marker, from); at >= 0; at = text.indexOf(marker, at + marker.length())) {
            int slashes = 0;
            for (int before = at - 1; before >= 0 && text.charAt(before) == '\\'; before--) slashes++;
            if (slashes % 2 == 0) return at;
        }
        return -1;
    }

    private static int matchingTicks(String text, String marker, int from) {
        for (int close = text.indexOf(marker, from); close >= 0; close = text.indexOf(marker, close + marker.length())) {
            int end = close + marker.length();
            if ((close == 0 || text.charAt(close - 1) != 96) && (end == text.length() || text.charAt(end) != 96))
                return close;
        }
        return -1;
    }
}
