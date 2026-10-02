package org.dyh.learnhub.service;

import org.dyh.learnhub.entity.WikiPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯函数回归测试：锁住 P0-D（依赖/组成判据）与 P1-2（索引页内容指纹）。
 * 两个被测方法都是 static 且不依赖 Spring 容器，可直接调用。
 */
class KnowledgePureHelpersTest {

    private static WikiPage page(String key, String title) {
        WikiPage p = new WikiPage();
        p.setTopicKey(key);
        p.setTitle(title);
        return p;
    }

    // ---------------- P1-2：索引页内容指纹 ----------------

    /** 指纹必须与传入顺序无关（两个调用方一个按标题查、一个不排序） */
    @Test
    void indexFingerprintIsOrderIndependent() {
        String a = EntityCompileService.indexFingerprint(
                List.of(page("entity-1", "A"), page("entity-2", "B"), page("entity-3", "C")));
        String b = EntityCompileService.indexFingerprint(
                List.of(page("entity-3", "C"), page("entity-1", "A"), page("entity-2", "B")));
        assertEquals(a, b);
    }

    /** 增页 / 改名 / 删页都要能被检出（这正是"待更新"标脏的依据） */
    @Test
    void indexFingerprintDetectsListChanges() {
        List<WikiPage> base = List.of(page("entity-1", "A"), page("entity-2", "B"));
        String baseFp = EntityCompileService.indexFingerprint(base);

        assertNotEquals(baseFp, EntityCompileService.indexFingerprint(
                List.of(page("entity-1", "A"), page("entity-2", "B"), page("entity-3", "C"))), "多一页必须变");
        assertNotEquals(baseFp, EntityCompileService.indexFingerprint(
                List.of(page("entity-1", "A"), page("entity-2", "B-renamed"))), "改名必须变");
        assertNotEquals(baseFp, EntityCompileService.indexFingerprint(
                List.of(page("entity-1", "A"))), "删一页必须变");
    }

    /** 不能退化成"只按页数"的弱指纹：页数相同但内容不同时也要变 */
    @Test
    void indexFingerprintIsNotJustACount() {
        String a = EntityCompileService.indexFingerprint(List.of(page("entity-1", "A"), page("entity-2", "B")));
        String b = EntityCompileService.indexFingerprint(List.of(page("entity-9", "X"), page("entity-8", "Y")));
        assertNotEquals(a, b, "页数相同、内容不同 —— 弱指纹（index-<页数>）会误判为没变");
    }

    // ---------------- P0-D：依赖 vs 组成 判据 ----------------

    /** 评估报告里的真实证据句：这是"依赖"，不是"组成部分" */
    @Test
    void springDependsOnJvmIsDependencyNotComposition() {
        String evidence = "Spring 是基于 Java 的，自然依赖于 JVM";
        assertTrue(KgOntology.looksLikeDependency(evidence));
        assertFalse(KgOntology.looksLikeComposition(evidence));
    }

    /** 真正的组成/归属句不能被误判成依赖（否则会把正确的归属边改错） */
    @Test
    void compositionSentencesAreNotDependency() {
        assertFalse(KgOntology.looksLikeDependency("栈属于 JVM 内存结构"));
        assertTrue(KgOntology.looksLikeComposition("栈属于 JVM 内存结构"));
        assertFalse(KgOntology.looksLikeDependency("自动装配属于启动流程的一部分"));
        assertTrue(KgOntology.looksLikeComposition("自动装配属于启动流程的一部分"));
    }

    /** 两种词都出现时**不改判**（避免误伤）—— 调用方要求 dep && !comp */
    @Test
    void mixedEvidenceIsNotRewritten() {
        String evidence = "Spring 依赖 JVM，是 Spring 的一部分";
        assertTrue(KgOntology.looksLikeDependency(evidence));
        assertTrue(KgOntology.looksLikeComposition(evidence));
        assertFalse(KgOntology.looksLikeDependency(evidence) && !KgOntology.looksLikeComposition(evidence));
    }
}
