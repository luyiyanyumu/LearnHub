package org.dyh.learnhub.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 一条"连接固定"的最小 HTTP/1.0 客户端（零依赖，纯静态，可脱离 Spring 单测）。
 *
 * <h3>为什么不用 HttpClient</h3>
 * {@code java.net.http.HttpClient} 不暴露 DNS 钩子：调用方先把域名解析成 IP 做了公网校验，
 * 它内部还会**再解析一次** —— 攻击者只要在两次解析之间改掉 DNS，就能让"校验的是公网、
 * 连的是内网"（DNS 重绑定）。DSH 的抓取提供方是"把连接钉在已校验地址上"，这里做等价的事：
 *
 * <pre>
 *   TCP 连到【已校验的 IP】 → HTTPS 时在其上做 TLS，且 SNI 与证书校验都用【主机名】
 * </pre>
 *
 * 这样"解析—校验—连接"之间没有第二次解析，窗口关闭；而证书仍按主机名校验，安全性不打折。
 *
 * <h3>用 HTTP/1.0 + Connection: close 的原因</h3>
 * 正文以 EOF 定界，不必实现 chunked 分帧（HTTP/1.0 请求下服务端不应使用 chunked）。
 * 仍然保留 chunked 解码兜底，因为总有不合规的实现。不请求压缩（identity），
 * 省掉 gzip 解压这一层。
 */
public final class PinnedHttpClient {

    private PinnedHttpClient() {
    }

    /** 一次响应：状态码 + 头（键已小写）+ 正文（null 表示超过字节上限） */
    public record Response(int status, Map<String, String> headers, byte[] body) {
        public String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }
    }

    /**
     * 向 {@code pinned} 发一次 GET。
     *
     * @param pinned           已校验过的目标地址（调用方负责公网校验）
     * @param uri              原始 URI，仅用于 Host / 路径 / SNI（**不用于解析**）
     * @param maxBytes         正文字节上限，超过返回 body=null
     * @param connectTimeoutMs 连接超时
     * @param readTimeoutMs    读取超时
     */
    public static Response get(InetAddress pinned, URI uri, String userAgent, long maxBytes,
                               int connectTimeoutMs, int readTimeoutMs) {
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        int port = portOf(uri);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(pinned, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            InputStream in;
            OutputStream out;
            if (https) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                // 关键：底层连的是已校验 IP，但 SNI 与证书链校验都用主机名
                SSLSocket ssl = (SSLSocket) factory.createSocket(socket, uri.getHost(), port, true);
                SSLParameters params = ssl.getSSLParameters();
                if (!isIpLiteral(uri.getHost())) {
                    params.setServerNames(List.of(new SNIHostName(uri.getHost())));
                }
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                in = ssl.getInputStream();
                out = ssl.getOutputStream();
            } else {
                in = socket.getInputStream();
                out = socket.getOutputStream();
            }

            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path = path + "?" + uri.getRawQuery();
            }
            int defaultPort = https ? 443 : 80;
            String hostHeader = uri.getHost() + (port == defaultPort ? "" : ":" + port);
            String request = "GET " + path + " HTTP/1.0\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: " + userAgent + "\r\n"
                    + "Accept: text/html,application/xhtml+xml,application/json;q=0.9,text/plain;q=0.8,*/*;q=0.5\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readResponse(in, maxBytes);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("抓取失败：" + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 关不掉不影响结果，别让它盖掉真正的异常
            }
        }
    }

    private static Response readResponse(InputStream in, long maxBytes) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] head = null;
        int b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (b == '\n') {
                byte[] cur = buf.toByteArray();
                int n = cur.length;
                boolean crlf = n >= 4 && cur[n - 4] == '\r' && cur[n - 3] == '\n'
                        && cur[n - 2] == '\r' && cur[n - 1] == '\n';
                boolean lf = n >= 2 && cur[n - 2] == '\n' && cur[n - 1] == '\n';
                if (crlf || lf) {
                    head = cur;
                    break;
                }
            }
            if (buf.size() > 128 * 1024) {
                throw new IllegalStateException("响应头异常大（超过 128 KB），已停止");
            }
        }
        if (head == null) {
            throw new IllegalStateException("连接被关闭，没读到任何响应头");
        }
        String[] lines = new String(head, StandardCharsets.ISO_8859_1).split("\r?\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
            throw new IllegalStateException("响应不是 HTTP：" + brief(lines.length > 0 ? lines[0] : "", 80));
        }
        String[] parts = lines[0].split(" ", 3);
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析状态行：" + brief(lines[0], 80));
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String lastKey = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && lastKey != null) {
                headers.computeIfPresent(lastKey, (k, v) -> v + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            lastKey = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            headers.put(lastKey, line.substring(colon + 1).trim());
        }

        byte[] body = headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")
                ? readChunked(in, maxBytes)
                : readBounded(in, maxBytes);
        body = decodeContentEncoding(headers.getOrDefault("content-encoding", ""), body, maxBytes);
        return new Response(status, headers, body);
    }

    /**
     * 压缩兜底。
     * 我们请求的是 {@code Accept-Encoding: identity}，服务端本不该压缩；
     * 但确实存在无视它的实现 —— 那会把二进制垃圾当正文交给模型，且"看起来成功"。
     * 与其静默出错，这里直接解压（解不开就由调用方按 charset 处理，至少不丢数据）。
     */
    private static byte[] decodeContentEncoding(String encoding, byte[] body, long maxBytes) {
        if (body == null || encoding == null || encoding.isBlank()) {
            return body;
        }
        String enc = encoding.toLowerCase(Locale.ROOT);
        try {
            if (enc.contains("gzip") || enc.contains("x-gzip")) {
                return readBounded(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body)), maxBytes);
            }
            if (enc.contains("deflate")) {
                return readBounded(new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(body)), maxBytes);
            }
        } catch (Exception e) {
            // 解不开就原样返回：让上层按声明的 charset 尽力解读
            return body;
        }
        return body;
    }

    private static byte[] readBounded(InputStream in, long maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > maxBytes) {
                return null;
            }
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    /** chunked 兜底（HTTP/1.0 请求下不该出现，但总有不合规的服务端） */
    private static byte[] readChunked(InputStream in, long maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (;;) {
            String line = readLine(in);
            if (line == null) {
                break;
            }
            String size = line.trim();
            int semi = size.indexOf(';');
            if (semi > 0) {
                size = size.substring(0, semi);
            }
            int n;
            try {
                n = Integer.parseInt(size.trim(), 16);
            } catch (Exception e) {
                throw new IllegalStateException("chunked 长度非法：" + brief(size, 32));
            }
            if (n == 0) {
                break;
            }
            if (out.size() + n > maxBytes) {
                return null;
            }
            byte[] chunk = new byte[n];
            int read = 0;
            while (read < n) {
                int r = in.read(chunk, read, n - read);
                if (r < 0) {
                    throw new IllegalStateException("chunked 正文被截断");
                }
                read += r;
            }
            out.write(chunk);
            readLine(in);
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b = -1;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
            if (buf.size() > 8192) {
                break;
            }
        }
        if (buf.size() == 0 && b < 0) {
            return null;
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    public static int portOf(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static String brief(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
