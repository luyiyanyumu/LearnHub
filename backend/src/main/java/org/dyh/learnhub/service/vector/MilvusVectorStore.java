package org.dyh.learnhub.service.vector;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.ai.EmbeddingClient;
import org.dyh.learnhub.entity.KbChunk;
import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.service.SettingsService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Milvus 实现（ANN），2026-09-29 加入。
 *
 * <h3>为什么用 REST 而不是官方 Java SDK</h3>
 * {@code milvus-sdk-java} 会带进整套 gRPC + protobuf（十几 MB 依赖 + 版本对齐问题），
 * 而这套代码只用到四件事：建集合、插向量、删来源、查近邻。
 * Milvus 2.4 起 v2 RESTful API 覆盖了全部这些（{@code /v2/vectordb/...}），
 * 用 JDK 自带的 {@link HttpClient} 就够 —— <b>不引入任何新依赖</b>也是这次接 Milvus 的硬要求之一。
 *
 * <h3>关键取舍</h3>
 * <ul>
 *   <li><b>集合按模型分</b>（{@code kb_chunk_bge_m3}）：换嵌入模型天生不冲突，
 *       不会出现"新旧向量维度/语义不同却混在一个索引里"。</li>
 *   <li><b>主键用 MySQL 的块 id</b>：检索回来直接 {@code selectBatchIds} 补文本，
 *       不用再维护一套"块 id ↔ 主键 id"的映射表。</li>
 *   <li><b>MySQL 始终写一份向量</b>：Milvus 挂了/写失败时，切回 mysql 后端立刻可用，
 *       不需要重新嵌入（这是"回退开关"能成立的前提）。</li>
 *   <li><b>健康检查带缓存</b>（30 秒）：Milvus 不可用时每轮对话都去连一次会拖慢回答。</li>
 * </ul>
 *
 * <h3>什么时候才该用它</h3>
 * 实测（1024 维）：1.3k 块全扫 3ms / 2 万块 60ms / 10 万块 294ms，
 * 而一次查询嵌入要 ~300ms —— 到 10 万块 ANN 最多省下 ~300ms。
 * 所以判据是"内存与并发"而不是"毫秒"：2 万块以上（向量常驻 80MB+）或出现并发查询时再切。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStore implements VectorStore {

    /** Milvus 地址（v2 RESTful 与 gRPC 同端口，默认 19530） */
    public static final String KEY_MILVUS_URI = "kb.milvus_uri";
    /** 认证：{@code user:password}（Milvus 默认 root:Milvus），留空表示不认证 */
    public static final String KEY_MILVUS_TOKEN = "kb.milvus_token";

    /** 单次插入的批大小：1024 维向量转成 JSON 后一行约 10KB，50 行 ≈ 0.5MB 请求体 */
    private static final int INSERT_BATCH = 50;
    /** 健康检查结果缓存时长：Milvus 挂了也不该让每轮对话都等一次连接超时 */
    private static final long HEALTH_TTL_MS = 30_000;
    /** "集合存在与否"的缓存时长：批量查询别每次都 list，但也别永久记错 */
    private static final long LIST_TTL_MS = 60_000;

    private final KbChunkMapper chunkMapper;
    private final EmbeddingClient embedder;
    private final SettingsService settingsService;
    private final ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private volatile long lastHealthAt;
    private volatile boolean lastHealthy;
    private volatile String lastHealthError = "";

    @Override
    public String name() {
        return "milvus";
    }

    // ------------------------------------------------------------------
    // 结构与写入
    // ------------------------------------------------------------------

    @Override
    public void ensure(String model, int dim) {
        String coll = collection(model);
        if (hasCollection(coll)) {
            return;
        }
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("autoID", false);
        schema.put("enableDynamicField", false);
        ArrayNode fields = schema.putArray("fields");
        fields.add(field("id", "Int64", true, false, null));
        fields.add(field("source_type", "VarChar", false, false, Map.of("max_length", "32")));
        fields.add(field("source_id", "Int64", false, false, null));
        fields.add(field("vector", "FloatVector", false, false, Map.of("dim", String.valueOf(dim))));

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("collectionName", coll);
        body.put("dimension", dim);
        body.put("metricType", "COSINE");
        body.put("primaryFieldName", "id");
        body.put("vectorFieldName", "vector");
        body.set("schema", schema);
        call("/v2/vectordb/collections/create", body);

        ObjectNode idx = JsonNodeFactory.instance.objectNode();
        idx.put("collectionName", coll);
        ArrayNode params = idx.putArray("indexParams");
        ObjectNode p = params.addObject();
        p.put("fieldName", "vector");
        p.put("indexName", "vector_idx");
        p.put("metricType", "COSINE");
        p.put("indexType", "AUTOINDEX");
        call("/v2/vectordb/indexes/create", idx);
        // 不 load 的话集合不可查（Milvus 语义）
        call("/v2/vectordb/collections/load", object("collectionName", coll));
        listCache.put(coll, new Exists(true, System.currentTimeMillis()));
        log.info("Milvus 集合已创建：{}（dim={}, metric=COSINE, AUTOINDEX）", coll, dim);
    }

    @Override
    public boolean hasVectors() {
        return hasCollection(collection(embedder.model()));
    }

    @Override
    public void replaceSource(String sourceType, Long sourceId, List<VecItem> items) {
        if (items.isEmpty()) {
            deleteSource(sourceType, sourceId);
            return;
        }
        String coll = collection(embedder.model());
        ensure(embedder.model(), items.get(0).vec().length);
        // 先删该来源：重新索引会分配新的块 id，只插不删会留下查不到正文的孤儿向量
        deleteSource(sourceType, sourceId);
        int done = 0;
        for (int i = 0; i < items.size(); i += INSERT_BATCH) {
            List<VecItem> batch = items.subList(i, Math.min(items.size(), i + INSERT_BATCH));
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("collectionName", coll);
            ArrayNode data = body.putArray("data");
            for (VecItem it : batch) {
                ObjectNode row = data.addObject();
                row.put("id", it.id());
                row.put("source_type", sourceType);
                row.put("source_id", sourceId);
                ArrayNode vec = row.putArray("vector");
                for (float f : it.vec()) {
                    vec.add(f);
                }
            }
            call("/v2/vectordb/entities/insert", body);
            done += batch.size();
        }
        log.info("Milvus 写入 {} 块（{}#{}）", done, sourceType, sourceId);
    }

    @Override
    public void deleteSource(String sourceType, Long sourceId) {
        String coll = collection(embedder.model());
        if (!hasCollection(coll)) {
            return;
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("collectionName", coll);
        body.put("filter", "source_type == \"" + sourceType + "\" && source_id == " + sourceId);
        call("/v2/vectordb/entities/delete", body);
    }

    @Override
    public void clear() {
        String coll = collection(embedder.model());
        if (!hasCollection(coll)) {
            return;
        }
        call("/v2/vectordb/collections/drop", object("collectionName", coll));
        listCache.remove(coll);
        log.info("Milvus 集合已清空（drop）：{}", coll);
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    @Override
    public List<VecHit> search(String model, float[] query, int pool) {
        if (query == null || query.length == 0) {
            return List.of();
        }
        String coll = collection(model);
        if (!hasCollection(coll)) {
            // 集合不存在 = 这个模型的向量还没建（或换了嵌入模型但没重建）。
            // 这里**不抛异常**：抛出去只会让调用方多做一次无意义的降级；
            // 返回空 = 本轮没有语义召回，与"嵌入服务挂了"的处理方式一致，且会留下日志。
            log.warn("Milvus 集合不存在，本轮无语义召回：{}（需要重建索引）", coll);
            return List.of();
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("collectionName", coll);
        body.put("annsField", "vector");
        ArrayNode data = body.putArray("data");
        ArrayNode vec = data.addArray();
        for (float f : query) {
            vec.add(f);
        }
        body.put("limit", Math.max(1, pool));
        ArrayNode out = body.putArray("outputFields");
        out.add("id");
        JsonNode result = call("/v2/vectordb/entities/search", body);

        // 两种返回形态都兼容：data 直接是数组，或包一层 data.data（不同小版本不一样）
        JsonNode arr = result.isArray() ? result : result.path("data");
        List<long[]> ids = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (JsonNode n : arr) {
            long id = n.path("id").asLong(n.path("entity").path("id").asLong(-1));
            double score = n.path("distance").asDouble(n.path("score").asDouble(Double.NaN));
            if (id <= 0 || Double.isNaN(score)) {
                continue;
            }
            ids.add(new long[]{id});
            scores.add(score);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        // 回 MySQL 补展示字段（文本/标题/来源）：Milvus 只负责"谁是近邻"
        List<Long> keys = ids.stream().map(a -> a[0]).toList();
        Map<Long, KbChunk> byId = new LinkedHashMap<>();
        for (KbChunk c : chunkMapper.selectBatchIds(keys)) {
            byId.put(c.getId(), c);
        }
        List<VecHit> hits = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            KbChunk c = byId.get(keys.get(i));
            if (c == null) {
                continue;   // 向量有、块行没了（删除竞态）：跳过，不返回半条结果
            }
            hits.add(new VecHit(c.getId(), c.getSourceType(), c.getSourceId(),
                    c.getSeq() == null ? 0 : c.getSeq(), c.getTitle(), c.getCategory(),
                    c.getChunkText(), scores.get(i)));
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // 健康与状态
    // ------------------------------------------------------------------

    /** 是否可用（带 30 秒缓存）。调用方据此决定降级，避免每轮都等连接超时。 */
    public boolean healthy() {
        long now = System.currentTimeMillis();
        if (now - lastHealthAt < HEALTH_TTL_MS) {
            return lastHealthy;
        }
        try {
            call("/v2/vectordb/collections/list", JsonNodeFactory.instance.objectNode());
            lastHealthy = true;
            lastHealthError = "";
        } catch (Exception e) {
            lastHealthy = false;
            lastHealthError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Milvus 不可用（本轮起降级为 MySQL 全扫检索）：{}", lastHealthError);
        }
        lastHealthAt = now;
        return lastHealthy;
    }

    /** 最近一次健康检查的错误（界面/日志用） */
    public String lastError() {
        return lastHealthError;
    }

    /**
     * 检索/写入时真的抛错了，就**立刻**标记不可用（不等 30 秒 TTL）。
     * <p>否则 Milvus 挂掉后的每轮对话都要先白等一次连接超时，才走降级 ——
     * 用户感觉是"回答变慢了"，而不是"Milvus 挂了"。
     */
    public void markUnhealthy(String reason) {
        lastHealthy = false;
        lastHealthAt = System.currentTimeMillis();
        lastHealthError = reason == null ? "unknown" : reason;
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("backend", name());
        o.put("uri", uri());
        o.put("healthy", healthy());
        String coll = collection(embedder.model());
        o.put("collection", coll);
        boolean exists = hasCollection(coll);
        o.put("collectionExists", exists);
        if (exists) {
            try {
                JsonNode d = call("/v2/vectordb/collections/describe", object("collectionName", coll));
                if (d.hasNonNull("rowCount")) {
                    o.put("rowCount", d.path("rowCount").asLong());
                }
            } catch (Exception e) {
                o.put("describeError", e.getMessage());
            }
        }
        if (!lastHealthError.isEmpty()) {
            o.put("lastError", lastHealthError);
        }
        return o;
    }

    /**
     * 体检用：Milvus 里的向量数（集合不存在返回 -1）。
     *
     * <p>注意 {@code collections/describe} **不返回 rowCount**（v2.4.15 实测），
     * 所以用 {@code entities/query} 的 {@code count(*)} 来数 —— 这也是两个后端"数量一致"的判据来源。
     */
    public long vectorCount() {
        String coll = collection(embedder.model());
        if (!hasCollection(coll)) {
            return -1;
        }
        try {
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("collectionName", coll);
            body.put("filter", "");
            ArrayNode out = body.putArray("outputFields");
            out.add("count(*)");
            JsonNode data = call("/v2/vectordb/entities/query", body);
            JsonNode arr = data.isArray() ? data : data.path("data");
            for (JsonNode n : arr) {
                if (n.has("count(*)")) {
                    return n.path("count(*)").asLong(-1);
                }
            }
            return -1;
        } catch (Exception e) {
            log.warn("统计 Milvus 向量数失败：{}", e.toString());
            return -1;
        }
    }

    /** 默认地址（供状态展示：设置里为空时也要显示真实值，否则界面上一片 null） */
    public String effectiveUri() {
        return uri();
    }

    /** MySQL 里的块数：与 Milvus 数量对不上就是"漂了"，体检要能看见 */
    public long mysqlChunkCount() {
        return chunkMapper.selectCount(Wrappers.<KbChunk>lambdaQuery().isNotNull(KbChunk::getVec));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private final Map<String, Exists> listCache = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean hasCollection(String name) {
        Exists cached = listCache.get(name);
        long now = System.currentTimeMillis();
        // 带 TTL：不让"集合存在与否"这件事永久缓存（否则在别处 drop / 重建后本进程会一直看错）
        if (cached != null && now - cached.at() < LIST_TTL_MS) {
            return cached.exists();
        }
        JsonNode data = call("/v2/vectordb/collections/list", JsonNodeFactory.instance.objectNode());
        JsonNode arr = data.isArray() ? data : data.path("data");
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode n : arr) {
            names.add(n.isTextual() ? n.asText() : n.path("collectionName").asText(""));
        }
        boolean exists = names.contains(name);
        listCache.put(name, new Exists(exists, now));
        return exists;
    }

    private record Exists(boolean exists, long at) {
    }

    @SuppressWarnings("unused")
    private void forgetCollection(String name) {
        listCache.remove(name);
    }

    private String collection(String model) {
        String m = model == null || model.isBlank() ? "unknown" : model;
        String slug = m.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return "kb_chunk_" + slug;
    }

    private String uri() {
        String v = settingsService.effective(KEY_MILVUS_URI);
        return v == null || v.isBlank() ? "http://127.0.0.1:19530" : v.trim();
    }

    private String token() {
        String v = settingsService.effective(KEY_MILVUS_TOKEN);
        return v == null || v.isBlank() ? "root:Milvus" : v.trim();
    }

    /** 发一条 REST 调用并返回 {@code data} 节点；code != 0 抛异常（调用方负责降级） */
    private JsonNode call(String path, ObjectNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(uri() + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Milvus HTTP " + resp.statusCode() + "：" + clip(resp.body()));
            }
            JsonNode root = json.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new IllegalStateException("Milvus code=" + code + "：" + root.path("message").asText());
            }
            return root.path("data");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 调用失败 " + path + "：" + e, e);
        }
    }

    private static ObjectNode field(String name, String type, boolean primary, boolean autoId,
                                    Map<String, String> elementTypeParams) {
        ObjectNode f = JsonNodeFactory.instance.objectNode();
        f.put("fieldName", name);
        f.put("dataType", type);
        if (primary) {
            f.put("isPrimary", true);
        }
        f.put("autoID", autoId);
        if (elementTypeParams != null) {
            ObjectNode p = f.putObject("elementTypeParams");
            elementTypeParams.forEach(p::put);
        }
        return f;
    }

    private static ObjectNode object(String key, String value) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put(key, value);
        return o;
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
