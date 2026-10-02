package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体页"来源指纹 + 依赖映射 + 过期判定"的回归测试（评估报告 P1-2，纯函数，不需要 Spring 容器）。
 *
 * <p>被测的四个方法都是 {@link EntityCompileService} 的 static 方法，锁住三件事：
 * <ol>
 *   <li>写库的格式（{@code src1|n3,r8}）与读取时的解析要对称 —— 对不上就会"永远新鲜"；</li>
 *   <li>存量实体页没有来源清单时要能退回**正文里的来源标注**，否则 36 个页永远不参与标脏；</li>
 *   <li>过期判定只认"来源被删 / 来源被改"，**无关页不能被标脏**（浪费 token 重编译）。</li>
 * </ol>
 */
class EntityStaleTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 12, 0, 0);

    // ---------------- 写：来源清单 → source_hash ----------------

    /** 格式固定为 src1| 前缀 + 类型简写，写库/读库要能对称 */
    @Test
    void fingerprintUsesVersionedCompactFormat() {
        assertEquals("src1|n3", EntityCompileService.sourcesFingerprint(List.of("note-3")));
        assertEquals("src1|f5,n3,r8",
                EntityCompileService.sourcesFingerprint(List.of("note-3", "quick_ref-8", "file-5")));
    }

    /** 指纹必须与传入顺序无关（来源是 Set/List 混着来的），并去掉重复 */
    @Test
    void fingerprintIsOrderIndependentAndDeduped() {
        String a = EntityCompileService.sourcesFingerprint(List.of("file-5", "note-3", "quick_ref-8"));
        String b = EntityCompileService.sourcesFingerprint(List.of("quick_ref-8", "note-3", "file-5", "note-3"));
        assertEquals(a, b);
        assertEquals("src1|f5,n3,r8", a);
    }

    /** 没有来源时不写任何东西（读取侧据此退回正文引用，而不是把 null 当成"无来源=过期"） */
    @Test
    void fingerprintOfNothingIsNull() {
        assertNull(EntityCompileService.sourcesFingerprint(null));
        assertNull(EntityCompileService.sourcesFingerprint(List.of()));
        // 认不出的 id 一律跳过，不会写出半个 id
        assertNull(EntityCompileService.sourcesFingerprint(List.of("?", "note-", "note-x")));
    }

    /**
     * 列宽是 VARCHAR(64) 且 MySQL 开着 STRICT_TRANS_TABLES：超长会直接报错、把整次编译打成失败。
     * 所以超长时必须在**完整 id 的边界**截断（漏记可接受，写出半个 id 会造成永久假阳性）。
     */
    @Test
    void fingerprintNeverExceedsColumnWidthAndCutsOnIdBoundary() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            many.add("note-" + (1000 + i));
        }
        String fp = EntityCompileService.sourcesFingerprint(many);
        assertTrue(fp.length() <= EntityCompileService.SOURCE_HASH_MAX, "超长会 Data too long：" + fp.length());
        // 截断后每一段都必须是完整可解析的 id
        List<String> ids = EntityCompileService.sourceIdsOf(fp, null);
        assertFalse(ids.isEmpty());
        assertTrue(ids.size() < many.size(), "30 个来源放不下，必须真的发生了截断");
        for (String id : ids) {
            assertTrue(id.matches("note-\\d{4}"), "截断必须落在完整 id 边界：" + id);
        }
    }

    // ---------------- 读：source_hash / 正文引用 → 来源 id ----------------

    /** 落库格式读回来要和写进去的一致 */
    @Test
    void parseRoundTripsFingerprint() {
        String fp = EntityCompileService.sourcesFingerprint(List.of("note-3", "quick_ref-8", "file-5"));
        assertEquals(List.of("file-5", "note-3", "quick_ref-8"), EntityCompileService.sourceIdsOf(fp, null));
    }

    /**
     * 存量实体页（source_hash 是旧的 {@code kind|count}、或根本为空）没有来源清单，
     * 必须退回正文里的来源标注 —— 否则 P1-2 对已有页面等于没做。
     */
    @Test
    void legacyPageFallsBackToCitationsInContent() {
        String md = "<!-- entity-aliases: Git -->\n## 概览\n版本控制 [笔记#3]，常用命令 [速查卡#8]。\n\n"
                + "## 关系\n配套工具见 [资料#5]，重复引用 [笔记#3]。\n";
        assertEquals(List.of("note-3", "quick_ref-8", "file-5"),
                EntityCompileService.sourceIdsOf("tool|2", md));
        assertEquals(List.of("note-3", "quick_ref-8", "file-5"),
                EntityCompileService.sourceIdsOf(null, md));
    }

    /** 没有任何来源信息 → 空表（调用方据此**不**标脏，不谎报） */
    @Test
    void noSourceInformationYieldsEmptyList() {
        assertEquals(List.of(), EntityCompileService.sourceIdsOf(null, null));
        assertEquals(List.of(), EntityCompileService.sourceIdsOf("index-a1b2c3", "## 概览\n没有出处的一段话。\n"));
        // 降级成纯文本的坏引用（没有方括号）不能被当成来源
        assertEquals(List.of(), EntityCompileService.sourceIdsOf(null, "## 概览\n引用了不存在的 笔记#99。\n"));
    }

    // ---------------- 判定：staleBySources ----------------

    /** 来源被删 → 过期；无关来源变化 → 不过期 */
    @Test
    void deletedSourceIsStale() {
        Map<String, LocalDateTime> times = Map.of("note-3", T0.minusDays(1));
        assertFalse(EntityCompileService.staleBySources(List.of("note-3"), times, T0));
        assertTrue(EntityCompileService.staleBySources(List.of("note-3", "file-5"), times, T0),
                "引用的 file-5 已经查不到了，这一页没有出处，必须标脏");
    }

    /** 来源在成页之后被改 → 过期；改在成页之前、或同一秒 → 不过期 */
    @Test
    void sourceUpdatedAfterGenerationIsStale() {
        assertTrue(EntityCompileService.staleBySources(List.of("note-3"),
                Map.of("note-3", T0.plusSeconds(1)), T0));
        assertFalse(EntityCompileService.staleBySources(List.of("note-3"),
                Map.of("note-3", T0.minusSeconds(1)), T0));
        assertFalse(EntityCompileService.staleBySources(List.of("note-3"),
                Map.of("note-3", T0), T0), "同一秒生成+保存不该反复标脏");
    }

    /** 多来源里只有改过的那个算数：其余无关来源不影响结论 */
    @Test
    void onlyChangedSourceOfManyIsStale() {
        Map<String, LocalDateTime> times = Map.of(
                "note-3", T0.minusDays(2),
                "quick_ref-8", T0.plusMinutes(5),
                "file-5", T0.minusDays(2));
        assertTrue(EntityCompileService.staleBySources(List.of("note-3", "quick_ref-8", "file-5"), times, T0));
        assertFalse(EntityCompileService.staleBySources(List.of("note-3", "file-5"), times, T0));
    }

    /** 成页时间缺失时只做"来源还在不在"的检查（不能因为比不了时间就一律标脏） */
    @Test
    void missingGeneratedAtOnlyChecksExistence() {
        Map<String, LocalDateTime> times = Map.of("note-3", T0.plusDays(9));
        assertFalse(EntityCompileService.staleBySources(List.of("note-3"), times, null));
        assertTrue(EntityCompileService.staleBySources(List.of("note-3", "file-5"), times, null));
    }

    /** 空来源清单 / 空时间表 → 不标脏（判断不了就不谎报） */
    @Test
    void unknownNeverMarksStale() {
        assertFalse(EntityCompileService.staleBySources(List.of(), Map.of("note-3", T0), T0));
        assertFalse(EntityCompileService.staleBySources(null, Map.of("note-3", T0), T0));
        assertFalse(EntityCompileService.staleBySources(List.of(), Map.of(), null));
    }
}
