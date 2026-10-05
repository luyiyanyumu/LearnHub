package org.dyh.learnhub.controller;

import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.entity.ModelProfile;
import org.dyh.learnhub.service.ModelProfileService;
import org.dyh.learnhub.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「模型分工」字段的契约测试。
 *
 * <h3>它守的是哪次事故</h3>
 * 2026-10：界面里给「阅读器翻译」那一行选档案 → 弹出
 * 「切换失败：不支持的设置项: modelForTranslate」，值根本没写进去。原因是
 * {@code SettingsController.FIELD_MAPPING} 里任务字段是**一行行手写的**，
 * 新增 {@code translate / formula} 任务时没跟着 {@link ModelRouting#allTasks()} 补一行
 *（同一个错此前已犯过三次：triple / rerank / grounding）。
 *
 * <p>这类故障单测最容易漏：跑业务流的测试只覆盖已登记的任务，漏登记的那个任务在测试里根本碰不到。
 * 所以这里直接检查映射与下发的字段本身 —— 任务清单里每个任务，都必须有一个能进白名单、
 * 能翻译成正确内部键的字段名；而分工表下发给前端的 {@code field} 必须与它同源。
 */
class SettingsControllerRoutingFieldTest {

    @Test
    @DisplayName("任务清单里每个任务都能通过 PUT /api/settings 改（字段能翻译成 ai.model_for_<task>）")
    @SuppressWarnings("unchecked")
    void everyTaskHasAnAcceptedField() {
        List<String> bad = new ArrayList<>();
        for (String task : ModelRouting.allTasks()) {
            String field = SettingsService.modelForTaskField(task);
            String value = "profile-for-" + task;
            // 每个任务一套独立的 mock：某一条失败不会污染下一条的断言
            SettingsService svc = mock(SettingsService.class);
            SettingsController ctl = new SettingsController(svc);

            Result<?> res = ctl.update(Map.of(field, value));
            if (res.getCode() != 200) {
                bad.add(field + "：" + res.getMsg());
                continue;
            }
            ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
            verify(svc).updateAll(captor.capture());
            String expectedKey = SettingsService.modelForTaskKey(task);
            String actual = captor.getValue().get(expectedKey);
            if (!value.equals(actual)) {
                bad.add(field + " 应翻译为 " + expectedKey + " → '" + value + "'，实际是 '" + actual
                        + "'（整份翻译：" + captor.getValue() + "）");
            }
            assertEquals("modelFor" + Character.toUpperCase(task.charAt(0)) + task.substring(1), field);
        }
        assertTrue(bad.isEmpty(), "这些任务在设置接口里走不通（漏登记 / 拼写不一致）：\n  "
                + String.join("\n  ", bad));
    }

    @Test
    @DisplayName("分工表下发的 field 与设置接口的派生同名（前端因此不用自己拼字段名）")
    @SuppressWarnings("unchecked")
    void routingTableCarriesTheSettingsFieldName() {
        List<Map<String, Object>> table = routing().table();
        List<String> tasks = new ArrayList<>();
        for (Map<String, Object> row : table) {
            String task = String.valueOf(row.get("task"));
            tasks.add(task);
            assertEquals(SettingsService.modelForTaskField(task), row.get("field"),
                    "分工表第 " + task + " 行下发的 field 与设置接口的派生不一致");
            // 下发的字段名必须真的能用（而不是"看起来对"）
            SettingsService svc = mock(SettingsService.class);
            SettingsController ctl = new SettingsController(svc);
            Result<?> res = ctl.update(Map.of(String.valueOf(row.get("field")), "prof-1"));
            assertEquals(200, res.getCode(),
                    "分工表下发的 field 用不了：" + row.get("field") + " → " + res.getMsg());
            ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
            verify(svc).updateAll(captor.capture());
            assertEquals("prof-1", captor.getValue().get(SettingsService.modelForTaskKey(task)));
        }
        assertTrue(tasks.containsAll(List.of("translate", "formula")),
                "翻译 / 公式这两个任务必须出现在分工表里，否则界面根本改不了它们：" + tasks);
        // 「对话问答」刻意不出现在分工表里（模型在智能体界面按会话选），见 ModelRouting.table()
        assertFalse(tasks.contains("chat"), "对话不分工表里出现：" + tasks);
    }

    private static ModelRouting routing() {
        SettingsService settings = mock(SettingsService.class);
        ModelProfileService profiles = mock(ModelProfileService.class);
        ModelProfile p = new ModelProfile();
        p.setId("prof-1");
        p.setName("云端");
        p.setProvider("deepseek");
        p.setBaseUrl("https://api.deepseek.com");
        p.setModel("deepseek-flash");
        when(profiles.all()).thenReturn(List.of(p));
        when(profiles.activeId()).thenReturn("prof-1");
        return new ModelRouting(settings, profiles);
    }

    @Test
    @DisplayName("接口能收的每个字段，其内部键都在 SettingsService 白名单里（否则 500 且值写不进去）")
    void everyMappedKeyIsWhitelisted() {
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, String> e : SettingsController.fieldMapping().entrySet()) {
            if (!SettingsService.knownKeys().contains(e.getValue())) {
                unknown.add(e.getKey() + " → " + e.getValue());
            }
        }
        assertTrue(unknown.isEmpty(),
                "这些字段接口收下了、但 SettingsService.update() 会抛「不支持的设置项」（界面只看到 500）：\n  "
                        + String.join("\n  ", unknown));
    }

    @Test
    @DisplayName("未登记字段仍然 400、且不会进入更新（避免错键静默把别的设置盖掉）")
    void unknownFieldIsRejectedAndDoesNotMutate() {
        SettingsService svc = mock(SettingsService.class);
        SettingsController ctl = new SettingsController(svc);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("polishPrompt", "...");      // 历史遗留字段：已迁到 skills/markdown-polish/SKILL.md
        body.put("modelForUnknownTask", "x"); // 任务清单里没有这个任务
        Result<?> res = ctl.update(body);
        assertEquals(400, res.getCode(), "未登记字段必须 400");
        assertTrue(res.getMsg().startsWith("不支持的设置项"),
                "错误信息应明确说「不支持的设置项」，实际：" + res.getMsg());
        verify(svc, never()).updateAll(any());
    }

    @Test
    @DisplayName("任务 → 字段名 的换算约定稳定（前端 SettingsDialog 也按这个拼，这条断言是两端契约的安全锁）")
    void fieldNameContractIsStable() {
        LinkedHashMap<String, String> expected = new LinkedHashMap<>();
        expected.put("chat", "modelForChat");
        expected.put("wiki", "modelForWiki");
        expected.put("entity", "modelForEntity");
        expected.put("impact", "modelForImpact");
        expected.put("lint", "modelForLint");
        expected.put("graph", "modelForGraph");
        expected.put("triple", "modelForTriple");
        expected.put("rerank", "modelForRerank");
        expected.put("grounding", "modelForGrounding");
        expected.put("rewrite", "modelForRewrite");
        expected.put("translate", "modelForTranslate");
        expected.put("formula", "modelForFormula");
        expected.forEach((task, field) ->
                assertEquals(field, SettingsService.modelForTaskField(task),
                        "约定变了：前端 SettingsDialog.fieldOfTask 与后端派生会一起坏掉"));
    }
}
