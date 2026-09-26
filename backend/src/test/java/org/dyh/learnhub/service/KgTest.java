package org.dyh.learnhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识图谱三块纯逻辑的单元测试：本体、实体消歧、规则推理。
 *
 * <p>这三块决定了图谱的正确性下限，而且**都是可以脱离数据库与模型单测的纯函数**，所以必须有护栏：
 * <ul>
 *   <li>本体：关系归一决定"模型输出什么都能落到封闭词表"，认不出来必须返回 null 而不是兜底；</li>
 *   <li>消歧：归一化决定"Git / git / Ｇit 是不是一个点"，错一次就是图上两个点；</li>
 *   <li>推理：闭包决定"推出来的事实对不对"，多推一条假事实比少推十条更糟。</li>
 * </ul>
 */
class KgTest {

    // ---------------- 本体：关系归一 ----------------

    @Test
    @DisplayName("关系同义写法应归一到规范 id")
    void relationSynonymsNormalize() {
        assertEquals("part_of", KgOntology.canonical("part_of"));
        assertEquals("part_of", KgOntology.canonical("Part-Of"));
        assertEquals("part_of", KgOntology.canonical("属于"));
        assertEquals("part_of", KgOntology.canonical("包含于"));
        assertEquals("is_a", KgOntology.canonical("instance_of"));
        assertEquals("is_a", KgOntology.canonical("是一种"));
        assertEquals("contrast_with", KgOntology.canonical("易混"));
        assertEquals("prerequisite", KgOntology.canonical("前提"));
        assertEquals("related_to", KgOntology.canonical("关联"));
    }

    @Test
    @DisplayName("不在本体里的关系必须丢弃（返回 null），不能兜底成 related_to")
    void unknownRelationIsDroppedNotNull() {
        // 兜底成 related_to 的后果：图里全是"相关"这种零信息边，既不能推理也不能过滤
        assertNull(KgOntology.canonical("发明了"));
        assertNull(KgOntology.canonical("哈哈哈"));
        assertNull(KgOntology.canonical(""));
        assertNull(KgOntology.canonical(null));
        // 但模型爱写的带上下文短语要能认出来
        assertEquals("contrast_with", KgOntology.canonical("A 与 B 易混，需要对比"));
    }

    @Test
    @DisplayName("关系代数属性要齐全：传递/对称决定能不能推导")
    void relationProperties() {
        assertTrue(KgOntology.byId("is_a").transitive());
        assertTrue(KgOntology.byId("part_of").transitive());
        assertTrue(KgOntology.byId("prerequisite").transitive());
        assertTrue(KgOntology.byId("contrast_with").symmetric());
        assertTrue(KgOntology.byId("related_to").symmetric());
        // used_for 既不是传递也不是对称 —— 这是刻意的，硬推会造出错误事实
        assertFalse(KgOntology.byId("used_for").transitive());
        assertFalse(KgOntology.byId("used_for").symmetric());
        assertTrue(KgOntology.known("part_of"));
        assertFalse(KgOntology.known("nope"));
    }

    // ---------------- 实体消歧：归一化与别名 ----------------

    @Test
    @DisplayName("同一个概念的写法差异必须归一到同一个 id")
    void entityIdIsCaseAndWidthInsensitive() {
        String base = EntityLinker.id("Git");
        assertEquals(base, EntityLinker.id("git"));
        assertEquals(base, EntityLinker.id(" GIT "));
        assertEquals(base, EntityLinker.id("Ｇit")); // 全角 G（NFKC 折半角）
        // 不同概念不能撞成一个 id
        assertFalse(base.equals(EntityLinker.id("GitHub")));
        assertFalse(base.equals(EntityLinker.id("Maven")));
    }

    @Test
    @DisplayName("括注与并列写法要抽成别名，且括注不参与身份判定")
    void aliasCandidates() {
        // 括注里的写法进别名表，主名不带括注（标签短、可读，而 dsh 不会丢）
        List<String> a = EntityLinker.aliasCandidates("DeepSeek Harness（dsh）");
        assertTrue(a.contains("dsh"), "应为: " + a);
        assertEquals("DeepSeek Harness", EntityLinker.display("DeepSeek Harness（dsh）"));

        List<String> b = EntityLinker.aliasCandidates("IoC / DI");
        assertTrue(b.contains("IoC"), "应为: " + b);
        assertTrue(b.contains("DI"), "应为: " + b);

        assertTrue(EntityLinker.aliasCandidates("Git").isEmpty());
    }

    @Test
    @DisplayName("括注不能改变身份：带括注与不带括注必须归一到同一个节点")
    void parenthesisIsNotPartOfIdentity() {
        // 这条是踩过的坑：NFKC 会把 （） 折成 ()，若"去两端标点"跑在"抽括注"之前，
        // 收尾的 ) 会被吃掉，别名一个都抽不出来，而且同一概念会长出多个节点。
        assertEquals(EntityLinker.id("DeepSeek Harness（dsh）"), EntityLinker.id("DeepSeek Harness"));
        assertEquals(EntityLinker.normalize("DeepSeek Harness（dsh）"), EntityLinker.normalize("DeepSeek Harness"));
    }

    @Test
    @DisplayName("实体名的合法性判定：整句话、纯数字、纯符号都不该成节点")
    void plausibleNameFilter() {
        assertTrue(EntityLinker.plausible("Git"));
        assertTrue(EntityLinker.plausible("IoC 与 DI"));
        assertTrue(EntityLinker.plausible("垃圾回收"));
        assertFalse(EntityLinker.plausible(""));
        assertFalse(EntityLinker.plausible("1234"));
        assertFalse(EntityLinker.plausible("---"));
        // 超长（用 repeat 保证真的超过 40 字）
        assertFalse(EntityLinker.plausible("很长的概念名字".repeat(8)));
        // 含句读 = 这是一句话，不是概念名（实测模型会给出"堆、栈、方法区"这种答案）
        assertFalse(EntityLinker.plausible("堆、栈、方法区"));
        assertFalse(EntityLinker.plausible("Spring Boot 是一个框架。"));
    }

    @Test
    @DisplayName("重复提示：归一化相等给满分，包含关系给中等分，无关给 0")
    void duplicateSuggestion() {
        assertEquals(1.0, EntityLinker.suggestScore("Git", "git"));
        assertTrue(EntityLinker.suggestScore("IoC", "IoC 与 DI") >= 0.6);
        assertEquals(0.0, EntityLinker.suggestScore("Maven", "垃圾回收"));
    }

    // ---------------- 推理：传递 / 对称闭包 ----------------

    @Test
    @DisplayName("传递关系要推出隐含事实，且权重按跳数衰减")
    void transitiveClosure() {
        List<KgReasoner.Edge> direct = List.of(
                new KgReasoner.Edge("1", "maven", "part_of", "build", 1.0),
                new KgReasoner.Edge("2", "build", "part_of", "engineering", 1.0));
        List<KgReasoner.Edge> derived = KgReasoner.derive(direct);

        assertEquals(1, derived.size(), "应推出一条：maven part_of engineering");
        KgReasoner.Edge d = derived.get(0);
        assertEquals("maven", d.head());
        assertEquals("part_of", d.relation());
        assertEquals("engineering", d.tail());
        // 推导出的可信度必须低于直接事实，且能溯源
        assertTrue(d.weight() < 1.0);
        assertTrue(d.key().contains("1") && d.key().contains("2"), "派生依据要带上门");
    }

    @Test
    @DisplayName("对称关系要补反向边")
    void symmetricClosure() {
        List<KgReasoner.Edge> direct = List.of(
                new KgReasoner.Edge("1", "a", "contrast_with", "b", 0.9));
        List<KgReasoner.Edge> derived = KgReasoner.derive(direct);
        assertEquals(1, derived.size());
        assertEquals("b", derived.get(0).head());
        assertEquals("a", derived.get(0).tail());
    }

    @Test
    @DisplayName("不满足代数性质的关系绝不推导（used_for 传递会造出假事实）")
    void nonTransitiveRelationIsNotDerived() {
        List<KgReasoner.Edge> direct = List.of(
                new KgReasoner.Edge("1", "a", "used_for", "b", 1.0),
                new KgReasoner.Edge("2", "b", "used_for", "c", 1.0));
        assertTrue(KgReasoner.derive(direct).isEmpty());
    }

    @Test
    @DisplayName("已有的事实不重复推导；自环不产生")
    void noDuplicatesOrSelfLoops() {
        List<KgReasoner.Edge> direct = List.of(
                new KgReasoner.Edge("1", "a", "part_of", "b", 1.0),
                new KgReasoner.Edge("2", "b", "part_of", "c", 1.0),
                // 已经存在的推导结果，不该再被当成新事实
                new KgReasoner.Edge("3", "a", "part_of", "c", 0.7));
        assertTrue(KgReasoner.derive(direct).isEmpty());
    }

    @Test
    @DisplayName("方向语义：非对称关系不能反向遍历，否则“栈属于JVM”会变成“JVM属于栈”")
    void traversalDirection() {
        assertTrue(KgReasoner.traversable("part_of", true));
        assertFalse(KgReasoner.traversable("part_of", false));
        assertTrue(KgReasoner.traversable("contrast_with", false));
        assertTrue(KgReasoner.traversable("related_to", false));
    }

    @Test
    @DisplayName("三元组唯一键与库里的唯一索引一致")
    void tripleKeyShape() {
        assertNotNull(KgReasoner.tripleKey("a", "part_of", "b"));
        assertEquals("a|part_of|b", KgReasoner.tripleKey("a", "part_of", "b"));
    }
}
