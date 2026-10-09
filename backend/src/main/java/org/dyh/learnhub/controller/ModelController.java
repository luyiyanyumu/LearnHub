package org.dyh.learnhub.controller;

import lombok.RequiredArgsConstructor;
import org.dyh.learnhub.ai.ModelRouting;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.service.AgentSessionService;
import org.dyh.learnhub.service.ModelProfileService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置档案 + 智能体会话管理。
 *
 * <h3>为什么单独一个控制器</h3>
 * 这两件事是一体的：**"用哪个模型"是可配置的多个档案**，**"哪次对话用哪个模型"由会话记住**。
 * 原来模型只有"主模型 / 本地目标"两组写死的设置键，会话也只能记个标题 ——
 * 现在档案是列表，每个任务各指一个，会话还可以自己覆盖。
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>{@code GET /api/model/profiles} 返回的档案**永不包含明文密钥**，只有 {@code hasKey} 与 {@code keyHint}；</li>
 *   <li>更新档案时 {@code apiKey} 传 {@code __KEEP__} = 保留原值（界面回传的是掩码，不能拿它覆盖真 key）；</li>
 *   <li>删除档案后，对话任务回退到默认对话档案；向量嵌入改为未配置。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelProfileService profiles;
    private final ModelRouting routing;
    private final AgentSessionService sessions;
    private final org.dyh.learnhub.service.ModelDiscoveryService discovery;

    // ---------------- 档案 ----------------

    /** 档案清单（脱敏）+ 提供方预设 + 各任务当前指向 */
    @GetMapping("/profiles")
    public Result<Map<String, Object>> list() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("profiles", profiles.list());
        o.put("presets", ModelProfileService.presets());
        o.put("activeId", profiles.activeId());
        o.put("routing", routing.table());
        return Result.ok(o);
    }

    @PostMapping("/profiles")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return Result.ok(profiles.create(body));
    }

    @PutMapping("/profiles/{id}")
    public Result<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return Result.ok(profiles.update(id, body));
    }

    @DeleteMapping("/profiles/{id}")
    public Result<Map<String, Object>> delete(@PathVariable String id) {
        return Result.ok(profiles.delete(id));
    }

    /** 激活某个档案（对话默认用它） */
    @PostMapping("/profiles/{id}/activate")
    public Result<Map<String, Object>> activate(@PathVariable String id) {
        return Result.ok(profiles.activate(id));
    }

    /**
     * 连通性探测：拿这个档案实际发一次最小请求。
     * <p>配了就跑一下 —— "填完不知道能不能用"是最常见的一类配置失败。
     */
    @PostMapping("/profiles/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable String id) {
        return Result.ok(profiles.probe(id));
    }

    /**
     * 按「地址 + 密钥」自动获取模型列表（{@code GET {baseUrl}/models}）。
     *
     * <p>用 POST 而不是 GET：body 里可能带着用户刚填、**还没保存**的密钥，不能出现在 URL / 访问日志里。
     * 编辑已有档案时密钥框留空即可，传 {@code profileId}，后端用库里那把。
     */
    @PostMapping("/discover")
    public Result<Map<String, Object>> discover(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return Result.ok(discovery.discover(str(b.get("profileId")), str(b.get("baseUrl")), str(b.get("apiKey"))));
    }

    /** 从老配置迁移（幂等：已有档案时返回 0） */
    @PostMapping("/profiles/migrate")
    public Result<Map<String, Object>> migrate() {
        int n = profiles.migrateLegacyIfEmpty();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("created", n);
        o.put("profiles", profiles.list());
        return Result.ok(o);
    }

    // ---------------- 任务分工 ----------------

    /** 7+ 个任务各自用哪个档案（现在是档案 id，不再是 main/local） */
    @GetMapping("/routing")
    public Result<List<Map<String, Object>>> routing() {
        return Result.ok(routing.table());
    }

    // ---------------- 会话 ----------------

    /** 会话列表（右边栏"新开/切换会话"用） */
    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> sessions(@RequestParam(required = false) Integer limit) {
        return Result.ok(sessions.list(limit == null ? 50 : limit));
    }

    /** 新建会话（可指定模型档案） */
    @PostMapping("/sessions")
    public Result<Map<String, Object>> newSession(@RequestBody(required = false) Map<String, Object> body) {
        String title = body == null ? null : str(body.get("title"));
        String profile = body == null ? null : str(body.get("modelProfileId"));
        profiles.requireChatProfile(profile);
        return Result.ok(sessions.createNew(title, profile));
    }

    @DeleteMapping("/sessions/{id}")
    public Result<Object> deleteSession(@PathVariable String id) {
        sessions.delete(id);
        return Result.ok(Map.of("deleted", id));
    }

    @PutMapping("/sessions/{id}/title")
    public Result<Object> renameSession(@PathVariable String id, @RequestBody Map<String, Object> body) {
        sessions.rename(id, str(body.get("title")));
        return Result.ok(Map.of("ok", true));
    }

    /** 会话指定模型档案；profileId 传空 = 跟随分工表 */
    @PutMapping("/sessions/{id}/model")
    public Result<Object> setSessionModel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        profiles.requireChatProfile(str(body.get("profileId")));
        sessions.setModelProfile(id, str(body.get("profileId")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("profileId", sessions.modelProfileOf(id));
        return Result.ok(result);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
