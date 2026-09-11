package org.dyh.learnhub.config;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.stdout.StdOutImpl;

import java.util.regex.Pattern;

/**
 * 带脱敏的 {@link StdOutImpl}：SQL 照旧打到控制台（方便学 SQL、排查问题），
 * 但把「不该出现在日志里」的内容遮掉。
 *
 * <p><b>它解决的具体问题：</b>应用设置（API Key、润色/格式提示词）都存在 app_setting 表里，
 * 而 application.yml 配了 {@code mybatis-plus.configuration.log-impl=...StdOutImpl}。
 * StdOutImpl 会把每条 SQL 的<b>参数值原样打印</b>，于是每次保存设置，
 * 完整密钥与提示词全文就进了控制台和日志文件。
 *
 * <p>这里只做两件事，其余一律透传（不影响正常看 SQL）：
 * <ol>
 *   <li>把各家风格的密钥串替换成 {@code «已脱敏»}；</li>
 *   <li>{@code ==> Parameters:} 这一行过长时截断并标注省略了多少字——提示词全文属于这一类。</li>
 * </ol>
 *
 * <p>MyBatis 会用反射按 {@code (String clazz)} 构造本类，所以这个构造函数必须是 public。
 * 生效方式见 {@link MybatisPlusConfig#maskingStdoutLogCustomizer()}，无需改 application.yml。
 */
public class MaskingStdOutImpl implements Log {

    /** 常见厂商密钥的形态（宁可多遮一点，也不要漏打真密钥） */
    private static final Pattern[] SECRET_PATTERNS = {
            // OpenAI / DeepSeek / Moonshot / 通义：sk-xxxx
            Pattern.compile("sk-[A-Za-z0-9_\\-]{4,}"),
            // Google：AIza...
            Pattern.compile("AIza[A-Za-z0-9_\\-]{10,}"),
            // 智谱：8位.16位.16位 的十六进制三段式
            Pattern.compile("[0-9a-fA-F]{8}\\.[0-9a-fA-F]{16}\\.[0-9a-fA-F]{16}"),
            // Authorization: Bearer xxx（如果将来有人把它带进 SQL）
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}"),
    };

    /** Parameters 行的最大保留长度：超过就认定是长文本（提示词等），截断 */
    private static final int PARAM_LINE_MAX = 200;
    private static final String MASK = "«已脱敏»";

    private final StdOutImpl delegate;

    public MaskingStdOutImpl(String clazz) {
        this.delegate = new StdOutImpl(clazz);
    }

    static String mask(String s) {
        if (s == null) {
            return null;
        }
        String out = s;
        for (Pattern p : SECRET_PATTERNS) {
            out = p.matcher(out).replaceAll(MASK);
        }
        if (out.startsWith("==> Parameters:") && out.length() > PARAM_LINE_MAX) {
            out = out.substring(0, PARAM_LINE_MAX)
                  + " …（参数过长，已省略 " + (out.length() - PARAM_LINE_MAX) + " 字）";
        }
        return out;
    }

    @Override
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return delegate.isTraceEnabled();
    }

    @Override
    public void error(String s, Throwable e) {
        delegate.error(mask(s), e);
    }

    @Override
    public void error(String s) {
        delegate.error(mask(s));
    }

    @Override
    public void debug(String s) {
        delegate.debug(mask(s));
    }

    @Override
    public void trace(String s) {
        delegate.trace(mask(s));
    }

    @Override
    public void warn(String s) {
        delegate.warn(mask(s));
    }
}
