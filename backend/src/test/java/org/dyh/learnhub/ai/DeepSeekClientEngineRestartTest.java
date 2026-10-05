package org.dyh.learnhub.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游引擎崩溃后的**重试判据**。
 *
 * <p>守的是 2026-10-05 那次线上 500：strata 上的 qwen3.8-flash-next-iq2_xs 子进程崩了，
 * 上游回 503 {@code "the engine stopped unexpectedly (exit code 3221226505);
 * the next request restarts it"} —— 它自己说了"下一次请求会重启引擎"，而我们当时把这次
 * 本可恢复的崩溃原样抛给了用户（阅读器翻译报 500）。这里的判据就是"上游是否给了这个承诺"。
 *
 * <p>反面同样重要：鉴权失败 / 模型名不存在这类错误重试也没用，重试只是让用户多等一遍
 * （翻译这条链路前端超时是 180 秒），所以判据刻意卡得很窄。
 */
class DeepSeekClientEngineRestartTest {

    /** 原样取自线上那次失败的响应体（只去掉了外层换行） */
    private static final String CRASHED = "{\"error\": {\"type\": \"server_error\", "
            + "\"message\": \"the engine stopped unexpectedly (exit code 3221226505); "
            + "the next request restarts it\"}}";

    @Test
    @DisplayName("上游承诺「下一次请求会重启引擎」→ 值得原样重试一次")
    void crashedEngineIsRetryable() {
        assertTrue(DeepSeekClient.engineRestartable(503, CRASHED));
        assertTrue(DeepSeekClient.engineRestartable(500, CRASHED));
        assertTrue(DeepSeekClient.engineRestartable(502, "engine stopped unexpectedly; will restart"));
        assertTrue(DeepSeekClient.engineRestartable(503, CRASHED.toUpperCase()));
    }

    @Test
    @DisplayName("鉴权 / 模型名 / 请求体过大 / 普通 503 都不重试（重试只是让用户多等一遍）")
    void nonRetryableErrors() {
        assertFalse(DeepSeekClient.engineRestartable(401,
                "{\"error\":{\"message\":\"Authentication Fails, Your api key: null is invalid\"}}"));
        assertFalse(DeepSeekClient.engineRestartable(404, "{\"error\":{\"message\":\"model not found\"}}"));
        assertFalse(DeepSeekClient.engineRestartable(413, "payload too large"));
        assertFalse(DeepSeekClient.engineRestartable(503, "{\"error\":{\"message\":\"service unavailable\"}}"));
        assertFalse(DeepSeekClient.engineRestartable(503, "{\"error\":{\"message\":\"engine overloaded\"}}"));
    }

    @Test
    @DisplayName("只有 5xx 才算；2xx / 4xx / 空 body 一律不重试")
    void onlyServerErrors() {
        assertFalse(DeepSeekClient.engineRestartable(200, CRASHED));
        assertFalse(DeepSeekClient.engineRestartable(429, CRASHED));
        assertFalse(DeepSeekClient.engineRestartable(503, null));
        assertFalse(DeepSeekClient.engineRestartable(503, ""));
    }
}
