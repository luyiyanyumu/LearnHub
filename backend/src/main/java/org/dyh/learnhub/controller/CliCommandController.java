package org.dyh.learnhub.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dyh.learnhub.common.Result;
import org.dyh.learnhub.dto.NoteEditRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 描述已注册的业务接口。命令执行仍经过原控制器及其校验。 */
@RestController
public class CliCommandController {
    private static final Set<RequestMethod> SUPPORTED_METHODS = EnumSet.of(
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
    private static final String CONTROLLER_PACKAGE = CliCommandController.class.getPackageName();
    private static final int SCHEMA_DEPTH = 5;
    private static final DefaultParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();
    private static final Map<String, String> TEXT = Map.of("type", "string");
    private static final Map<String, String> INTEGER = Map.of("type", "integer");
    private static final Map<String, String> NUMBER = Map.of("type", "number");
    private static final Map<String, String> BOOLEAN = Map.of("type", "boolean");

    private final RequestMappingHandlerMapping mappings;
    private final ObjectMapper json;
    private final JsonNode noteOperationsSchema;

    public CliCommandController(@Lazy @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
                                ObjectMapper json) {
        this.mappings = mappings;
        this.json = json;
        this.noteOperationsSchema = readNoteOperationsSchema(json);
    }

    @GetMapping("/api/cli/commands")
    public Result<Catalog> commands() {
        List<Route> routes = routes();
        Map<String, Integer> counts = new HashMap<>();
        routes.forEach(route -> counts.merge(route.baseId(), 1, Integer::sum));
        List<Command> commands = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Route route : routes) {
            String id = route.baseId();
            if (counts.get(id) > 1) {
                id += "-" + route.method().name().toLowerCase(Locale.ROOT)
                        + "-" + Integer.toUnsignedString(route.path().hashCode(), 36);
            }
            // 路径散列冲突或同名重载也按稳定的路由顺序分配唯一 ID。
            String uniqueId = id;
            for (int suffix = 2; !seen.add(uniqueId); suffix++) uniqueId = id + "-" + suffix;
            commands.add(describe(uniqueId, route));
        }
        return Result.ok(new Catalog(1, List.copyOf(commands)));
    }

    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            Class<?> owner = ClassUtils.getUserClass(handler.getBeanType());
            if (!owner.getPackageName().equals(CONTROLLER_PACKAGE) || owner == CliCommandController.class) return;
            String controller = owner.getSimpleName().replaceFirst("Controller$", "");
            String baseId = kebab(controller) + "." + kebab(handler.getMethod().getName());
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/") || path.startsWith("/api/cli/")) continue;
                for (RequestMethod method : mapping.getMethodsCondition().getMethods()) {
                    if (SUPPORTED_METHODS.contains(method)) routes.add(new Route(baseId, method, path, mapping, handler));
                }
            }
        });
        routes.sort(Comparator.comparing(Route::baseId)
                .thenComparing(Route::method).thenComparing(Route::path)
                .thenComparing(route -> route.mapping().toString())
                .thenComparing(route -> route.handler().getMethod().toGenericString()));
        return routes;
    }

    private Command describe(String id, Route route) {
        MethodParameter[] parameters = route.handler().getMethodParameters();
        boolean multipart = route.mapping().getConsumesCondition().getConsumableMediaTypes().stream()
                .anyMatch(MediaType.MULTIPART_FORM_DATA::includes)
                || Arrays.stream(parameters).anyMatch(parameter -> isFile(json.constructType(parameter.getGenericParameterType()))
                        || parameter.hasParameterAnnotation(RequestPart.class));
        List<Argument> arguments = new ArrayList<>();
        Body body = null;
        for (MethodParameter parameter : parameters) {
            parameter.initParameterNameDiscovery(PARAMETER_NAMES);
            JavaType type = json.constructType(parameter.getGenericParameterType());
            RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
            if (requestBody != null) {
                body = new Body(requestBody.required(), bodySchema(route.baseId(), type));
                continue;
            }
            Argument argument = argument(parameter, type, multipart);
            if (argument != null) arguments.add(argument);
        }
        return new Command(id, route.method().name(), route.path(), List.copyOf(arguments), body, response(route));
    }

    private Argument argument(MethodParameter parameter, JavaType type, boolean multipart) {
        String valueType = valueType(type);
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            return new Argument(name(path.name(), path.value(), parameter), "path", path.required(), valueType, null);
        }
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            return withDefault(name(header.name(), header.value(), parameter), "header", header.required(), valueType, header.defaultValue());
        }
        RequestPart part = parameter.getParameterAnnotation(RequestPart.class);
        if (part != null) {
            return new Argument(name(part.name(), part.value(), parameter), isFile(type) ? "file" : "form",
                    part.required(), isFile(type) && !type.isContainerType() ? "string" : valueType, null);
        }
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            boolean file = isFile(type);
            return withDefault(name(query.name(), query.value(), parameter), file ? "file" : multipart ? "form" : "query",
                    query.required(), file && !type.isContainerType() ? "string" : valueType, query.defaultValue());
        }
        return null;
    }

    private static Argument withDefault(String name, String in, boolean required, String type, String defaultValue) {
        String value = ValueConstants.DEFAULT_NONE.equals(defaultValue) ? null : defaultValue;
        return new Argument(name, in, required && value == null, type, value);
    }

    private static String name(String name, String value, MethodParameter parameter) {
        if (!name.isEmpty()) return name;
        if (!value.isEmpty()) return value;
        String inferred = parameter.getParameterName();
        if (inferred != null) return inferred;
        throw new IllegalStateException("命令参数缺少名称：" + parameter);
    }

    private Map<String, Object> bodySchema(String id, JavaType type) {
        if (type.hasRawClass(NoteEditRequest.class) && noteOperationsSchema != null) {
            return Map.of("type", "object", "required", List.of("expected_hash", "operations"),
                    "properties", Map.of("expected_hash", Map.of("type", "string", "description",
                                    "使用 note.detail 返回的 contentHash，防止覆盖过期正文。"),
                            "operations", noteOperationsSchema));
        }
        if ("settings.update".equals(id)) {
            Map<String, Object> properties = new LinkedHashMap<>();
            SettingsController.fieldMapping().keySet().stream().sorted()
                    .forEach(field -> properties.put(field, TEXT));
            return Map.of("type", "object", "properties", properties, "additionalProperties", false);
        }
        Map<String, Object> result = schema(type, 0);
        if (!type.isMapLikeType()) return result;

        Map<String, Object> hints = mapFieldHints(id);
        if (!hints.isEmpty()) result.put("properties", hints);
        switch (id) {
            case "code.import-folder" -> result.put("required", List.of("files"));
            case "knowledge-maintenance.update" -> result.put("required", List.of("enabled"));
            default -> { }
        }
        return result;
    }

    /** Map 请求没有可内省字段。仅补充业务约定的字段提示，校验仍以原接口为准。 */
    private static Map<String, Object> mapFieldHints(String id) {
        return switch (id) {
            case "ai.save-skill" -> Map.of("content", TEXT, "name", TEXT, "description", TEXT, "appliesTo", TEXT, "minRatio", NUMBER);
            case "code.save-repo", "code.update-repo" -> textFields("name", "url", "demoUrl", "license", "note");
            case "code.save-snippet", "code.update-snippet" -> {
                var fields = textFields("title", "lang", "code", "explainText", "filePath", "sourceUrl");
                fields.put("repoId", INTEGER);
                yield fields;
            }
            case "code.import-folder" -> Map.of("projectName", TEXT, "repoId", INTEGER, "files",
                    Map.of("type", "array", "items", Map.of("type", "object", "properties", textFields("path", "text"),
                            "required", List.of("path", "text"))));
            case "file.update-summary" -> textFields("summary");
            case "file.rename" -> textFields("name");
            case "file.update-category" -> Map.of("categoryId", INTEGER);
            case "file.translate" -> textFields("text", "targetLang");
            case "file.save-reading-state" -> Map.of("page", INTEGER, "scale", NUMBER, "mode", TEXT);
            case "knowledge-maintenance.update" -> Map.of("enabled", BOOLEAN);
            case "knowledge-settings.update" -> Map.of("values", Map.of("type", "object", "description",
                    "设置项名称和类型可从 knowledge-settings.get 查看。"));
            case "model.create", "model.update" -> {
                var fields = textFields("name", "provider", "purpose", "baseUrl", "model", "note", "thinking", "reasoningEffort");
                fields.put("maxTokens", INTEGER);
                fields.put("temperature", NUMBER);
                fields.put("apiKey", Map.of("type", "string", "writeOnly", true));
                yield fields;
            }
            case "model.discover" -> Map.of("profileId", TEXT, "baseUrl", TEXT, "apiKey", Map.of("type", "string", "writeOnly", true));
            case "model.new-session" -> textFields("title", "modelProfileId");
            case "model.rename-session" -> textFields("title");
            case "model.set-session-model" -> textFields("profileId");
            case "vector.grounding-check" -> textFields("question", "evidence", "answer");
            case "wiki.impact" -> textFields("text");
            default -> Map.of();
        };
    }

    private static Map<String, Object> textFields(String... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : fields) properties.put(field, TEXT);
        return properties;
    }

    private static JsonNode readNoteOperationsSchema(ObjectMapper json) {
        try (var source = new ClassPathResource("note-edit-tool.json").getInputStream()) {
            JsonNode operations = json.readTree(source).at("/properties/operations");
            return operations.isObject() ? operations : null;
        } catch (IOException unavailable) {
            return null;
        }
    }

    private Map<String, Object> schema(JavaType type, int depth) {
        if (type == null || type.isJavaLangObject() || depth >= SCHEMA_DEPTH) return Map.of();
        if (type.isReferenceType()) return schema(type.getReferencedType(), depth + 1);
        Class<?> raw = type.getRawClass();
        String kind = valueType(type);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", kind);
        if (raw.isEnum()) {
            result.put("enum", Arrays.stream(raw.getEnumConstants()).map(value -> json.valueToTree(value)).toList());
        } else if ("array".equals(kind)) {
            result.put("items", schema(type.getContentType(), depth + 1));
        } else if (type.isMapLikeType()) {
            result.put("additionalProperties", schema(type.getContentType(), depth + 1));
        } else if ("object".equals(kind) && raw.getPackageName().startsWith("org.dyh.learnhub.")) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            json.getDeserializationConfig().introspect(type).findProperties().forEach(property -> {
                if (!property.couldDeserialize()) return;
                AnnotatedMember member = property.getPrimaryMember();
                Map<String, Object> details = new LinkedHashMap<>(schema(property.getPrimaryType(), depth + 1));
                if (property.isRequired() || isRequired(member)) required.add(property.getName());
                applySize(details, member);
                properties.put(property.getName(), details);
            });
            result.put("properties", properties);
            if (!required.isEmpty()) result.put("required", required);
        }
        return result;
    }

    private static boolean isRequired(AnnotatedMember member) {
        return member != null && (member.hasAnnotation(NotBlank.class) || member.hasAnnotation(NotEmpty.class)
                || member.hasAnnotation(NotNull.class));
    }

    private static void applySize(Map<String, Object> details, AnnotatedMember member) {
        if (member == null) return;
        String suffix = switch (String.valueOf(details.get("type"))) {
            case "string" -> "Length";
            case "array" -> "Items";
            case "object" -> "Properties";
            default -> null;
        };
        if (suffix == null) return;
        Size size = member.getAnnotation(Size.class);
        int min = size == null ? 0 : size.min();
        if (member.hasAnnotation(NotBlank.class) || member.hasAnnotation(NotEmpty.class)) min = Math.max(min, 1);
        if (min > 0) details.put("min" + suffix, min);
        if (size != null && size.max() < Integer.MAX_VALUE) details.put("max" + suffix, size.max());
    }

    private static boolean isFile(JavaType type) {
        return MultipartFile.class.isAssignableFrom(type.getRawClass())
                || (type.getContentType() != null && isFile(type.getContentType()));
    }

    private static String valueType(JavaType type) {
        if (type.isReferenceType()) return valueType(type.getReferencedType());
        Class<?> raw = ClassUtils.resolvePrimitiveIfNecessary(type.getRawClass());
        if (raw == Boolean.class) return "boolean";
        if (raw == Byte.class || raw == Short.class || raw == Integer.class || raw == Long.class
                || raw == java.math.BigInteger.class) return "integer";
        if (Number.class.isAssignableFrom(raw)) return "number";
        if (CharSequence.class.isAssignableFrom(raw) || raw == Character.class || raw.isEnum()
                || raw == java.util.UUID.class || java.time.temporal.TemporalAccessor.class.isAssignableFrom(raw)) return "string";
        if (type.isArrayType() || type.isCollectionLikeType()) return "array";
        return "object";
    }

    private String response(Route route) {
        JavaType type = json.constructType(route.handler().getReturnType().getGenericParameterType());
        if (HttpEntity.class.isAssignableFrom(type.getRawClass()) && type.containedType(0) != null) {
            type = type.containedType(0);
        }
        Class<?> raw = type.getRawClass();
        if (SseEmitter.class.isAssignableFrom(raw) || route.mapping().getProducesCondition().getProducibleMediaTypes()
                .stream().anyMatch(MediaType.TEXT_EVENT_STREAM::equals)) return "sse";
        if (Resource.class.isAssignableFrom(raw) || raw == byte[].class || StreamingResponseBody.class.isAssignableFrom(raw)) return "binary";
        return CharSequence.class.isAssignableFrom(raw) ? "text" : "json";
    }

    private static String kebab(String name) {
        return name.replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private record Route(String baseId, RequestMethod method, String path, RequestMappingInfo mapping, HandlerMethod handler) { }

    public record Catalog(int version, List<Command> commands) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Command(String id, String method, String path, List<Argument> parameters, Body body, String response) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Argument(String name, String in, boolean required, String type, @JsonProperty("default") String defaultValue) { }

    public record Body(boolean required, Map<String, Object> schema) { }
}
