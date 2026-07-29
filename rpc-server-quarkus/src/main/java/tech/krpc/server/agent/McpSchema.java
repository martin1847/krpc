package tech.krpc.server.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.krpc.common.meta.Anno;
import tech.krpc.common.meta.Api;
import tech.krpc.common.meta.ApiMeta;
import tech.krpc.common.meta.Dto;
import tech.krpc.common.meta.Method;
import tech.krpc.common.meta.Property;
import tech.krpc.common.meta.PropertyType;

/**
 * ADR-0004 (AGENT-001 P1): transforms the live agentTool {@link ApiMeta} into MCP tool
 * definitions (MCP spec 2025-06-18, {@code tools/list}).
 *
 * <p>Each {@code @UnsafeWeb(agentTool=true)} method becomes one tool:
 * <ul>
 *   <li>{@code name} = {@code Service_method} ({@link McpToolRegistry#toolName}).</li>
 *   <li>{@code description} from method {@code @Doc}, with the {@code RpcResult<T>}
 *       unwrap semantics stated (the wire envelope carries {@code code}/{@code message};
 *       {@code structuredContent} is the unwrapped {@code data}).</li>
 *   <li>{@code inputSchema} (always {@code type:object}, per MCP) derived from the single
 *       method arg's DTO type tree + jakarta constraints + {@code @Doc}.</li>
 *   <li>{@code outputSchema} from the already-{@code RpcResult}-unwrapped return type.</li>
 * </ul>
 *
 * <p>Arg-shape rule (the one place it is decided; dispatch reads the same
 * {@link #argIsObject}): an object-like arg (custom DTO, {@code Map}, or no arg) maps its
 * fields directly onto the arguments object and {@code tools/call} forwards the arguments
 * verbatim as the krpc JSON input; a scalar/array/enum arg is wrapped under a single
 * {@code "value"} property and {@code tools/call} unwraps {@code arguments.value}.
 */
final class McpSchema {

    private McpSchema() {}

    /** Bounds DTO-tree recursion (defensive; matches the framework's bounded reflection). */
    private static final int MAX_DEPTH = 12;

    static final String WRAP_KEY = "value";

    /**
     * MCP tool definitions for every method in {@code meta} (empty list if none), sorted by
     * tool name. MCP spec 2026-07-28 SHOULD-orders a listing deterministically: reflection
     * order is not stable across JVMs/builds, and an unstable list defeats client-side caching
     * (§ ttlMs/cacheScope) and makes diffing two servers noisy.
     */
    static List<Map<String, Object>> toolDefs(ApiMeta meta) {
        var tools = new ArrayList<Map<String, Object>>();
        if (null == meta || null == meta.getApis()) {
            return tools;
        }
        for (Api api : meta.getApis()) {
            if (null == api.getMethods()) {
                continue;
            }
            for (Method m : api.getMethods()) {
                tools.add(toolDef(api, m));
            }
        }
        tools.sort(java.util.Comparator.comparing(t -> (String) t.get("name")));
        return tools;
    }

    private static Map<String, Object> toolDef(Api api, Method m) {
        var tool = new LinkedHashMap<String, Object>();
        tool.put("name", McpToolRegistry.toolName(api.getName(), m.getName()));
        tool.put("description", description(m));
        tool.put("inputSchema", inputSchema(m.getArg()));
        var out = schemaOf(m.getRes(), 0, new ArrayList<>());
        // outputSchema SHOULD be an object; only emit when we can express one.
        if ("object".equals(out.get("type"))) {
            tool.put("outputSchema", out);
        }
        return tool;
    }

    private static String description(Method m) {
        var doc = docText(m.getAnnotations());
        var sb = new StringBuilder();
        // AGENT-002 finding #4: when a method has no @Doc, do NOT echo "Service.method" as a
        // fake description head (it told an agent nothing it didn't already have from `name`).
        // Emit only the honest RpcResult-envelope note; DTO field-level @Doc still flows into
        // inputSchema/outputSchema untouched.
        if (null != doc && !doc.isBlank()) {
            sb.append(doc).append("\n\n");
        }
        sb.append("(krpc: the call returns an RpcResult envelope {code,message,data}; "
                + "code 0 = success. structuredContent is the unwrapped data.)");
        return sb.toString();
    }

    // --- input --------------------------------------------------------------------------

    /** Whether the method arg maps onto the arguments object directly (vs. wrapped). */
    static boolean argIsObject(PropertyType arg) {
        if (null == arg || null == arg.getRawType()) {
            return true; // no-arg: empty object, forwarded verbatim
        }
        var dto = arg.getRawType();
        if ("Map".equals(dto.getName())) {
            return true;
        }
        return isCustomObject(dto);
    }

    private static Map<String, Object> inputSchema(PropertyType arg) {
        if (argIsObject(arg)) {
            if (null == arg || null == arg.getRawType()) {
                var s = new LinkedHashMap<String, Object>();
                s.put("type", "object");
                s.put("properties", new LinkedHashMap<>());
                return s;
            }
            return schemaOf(arg, 0, new ArrayList<>());
        }
        // scalar / array / enum -> wrap under "value" so inputSchema stays an object.
        var props = new LinkedHashMap<String, Object>();
        props.put(WRAP_KEY, schemaOf(arg, 0, new ArrayList<>()));
        var s = new LinkedHashMap<String, Object>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", new ArrayList<>(List.of(WRAP_KEY)));
        return s;
    }

    // --- core type -> JSON Schema -------------------------------------------------------

    private static Map<String, Object> schemaOf(PropertyType pt, int depth, List<String> path) {
        var schema = new LinkedHashMap<String, Object>();
        if (null == pt || null == pt.getRawType() || depth > MAX_DEPTH) {
            return schema; // {} = accept anything (unresolved generic / too deep)
        }
        Dto dto = pt.getRawType();
        String name = dto.getName();

        if (dto.isParameterized()) {
            return schema; // bare type variable T -> any
        }

        switch (name) {
            case "String", "char", "Character", "CharSequence" -> schema.put("type", "string");
            case "byte[]" -> {
                schema.put("type", "string");
                schema.put("contentEncoding", "base64");
            }
            case "int", "Integer", "long", "Long", "short", "Short",
                 "byte", "Byte", "BigInteger" -> schema.put("type", "integer");
            case "double", "Double", "float", "Float", "BigDecimal" -> schema.put("type", "number");
            case "boolean", "Boolean" -> schema.put("type", "boolean");
            case "List", "Set", "Collection" -> {
                schema.put("type", "array");
                var gens = pt.getGenerics();
                schema.put("items", (null != gens && !gens.isEmpty())
                        ? schemaOf(gens.get(0), depth + 1, path) : new LinkedHashMap<>());
            }
            case "Map" -> {
                schema.put("type", "object");
                var gens = pt.getGenerics();
                schema.put("additionalProperties", (null != gens && gens.size() == 2)
                        ? schemaOf(gens.get(1), depth + 1, path) : Boolean.TRUE);
            }
            default -> {
                if (isEnum(dto)) {
                    schema.put("type", "string");
                    var vals = new ArrayList<String>();
                    for (Property f : dto.getFields()) {
                        vals.add(f.getName());
                    }
                    schema.put("enum", vals);
                } else if (isCustomObject(dto)) {
                    objectSchema(dto, schema, depth, path);
                } else {
                    // unknown java.* scalar (Object, etc.) -> unconstrained
                    schema.clear();
                }
            }
        }
        if (null != dto.getDoc() && !dto.getDoc().isBlank() && !schema.isEmpty()) {
            schema.putIfAbsent("description", dto.getDoc());
        }
        return schema;
    }

    private static void objectSchema(Dto dto, Map<String, Object> schema, int depth, List<String> path) {
        schema.put("type", "object");
        if (path.contains(dto.getName())) {
            return; // cycle: emit a bare object, do not recurse
        }
        path.add(dto.getName());
        var props = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (Property f : dto.getFields()) {
            var fieldSchema = schemaOf(f.getType(), depth + 1, path);
            applyConstraints(fieldSchema, f.getAnnotations(), required, f.getName());
            props.put(f.getName(), fieldSchema);
        }
        path.remove(path.size() - 1);
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
    }

    // --- jakarta constraints + @Doc -> schema facets ------------------------------------

    private static void applyConstraints(Map<String, Object> fieldSchema, List<Anno> annos,
                                         List<String> required, String fieldName) {
        if (null == annos) {
            return;
        }
        for (Anno a : annos) {
            var props = a.getProperties();
            switch (a.getName()) {
                case "Doc" -> {
                    var v = prop(props, "value");
                    if (v instanceof String s && !s.isBlank()) {
                        fieldSchema.put("description", s);
                    }
                }
                case "NotNull", "NotEmpty" -> addRequired(required, fieldName);
                case "NotBlank" -> {
                    addRequired(required, fieldName);
                    if ("string".equals(fieldSchema.get("type"))) {
                        fieldSchema.putIfAbsent("minLength", 1);
                    }
                }
                case "Size" -> {
                    boolean array = "array".equals(fieldSchema.get("type"));
                    var min = prop(props, "min");
                    var max = prop(props, "max");
                    if (min instanceof Number n && n.intValue() > 0) {
                        fieldSchema.put(array ? "minItems" : "minLength", n.intValue());
                    }
                    if (max instanceof Number n && n.intValue() < Integer.MAX_VALUE) {
                        fieldSchema.put(array ? "maxItems" : "maxLength", n.intValue());
                    }
                }
                case "Min", "DecimalMin" -> {
                    var v = prop(props, "value");
                    if (v instanceof Number n) {
                        fieldSchema.put("minimum", n);
                    } else if (v instanceof String s) {
                        try { fieldSchema.put("minimum", Long.parseLong(s)); } catch (NumberFormatException ignore) {}
                    }
                }
                case "Max", "DecimalMax" -> {
                    var v = prop(props, "value");
                    if (v instanceof Number n) {
                        fieldSchema.put("maximum", n);
                    } else if (v instanceof String s) {
                        try { fieldSchema.put("maximum", Long.parseLong(s)); } catch (NumberFormatException ignore) {}
                    }
                }
                case "Pattern" -> {
                    var v = prop(props, "regexp");
                    if (v instanceof String s && !s.isBlank()) {
                        fieldSchema.put("pattern", s);
                    }
                }
                case "Email" -> fieldSchema.putIfAbsent("format", "email");
                default -> { /* other annotations: no schema facet */ }
            }
        }
    }

    private static void addRequired(List<String> required, String fieldName) {
        if (!required.contains(fieldName)) {
            required.add(fieldName);
        }
    }

    // --- helpers ------------------------------------------------------------------------

    private static boolean isCustomObject(Dto dto) {
        return null != dto.getFields() && !dto.getFields().isEmpty() && !isEnum(dto);
    }

    /** An enum Dto's fields are the constants: name set, type null (see cls2dto). */
    private static boolean isEnum(Dto dto) {
        var fields = dto.getFields();
        if (null == fields || fields.isEmpty()) {
            return false;
        }
        for (Property f : fields) {
            if (null != f.getType()) {
                return false;
            }
        }
        return true;
    }

    private static Object prop(Map<String, Object> props, String key) {
        return null == props ? null : props.get(key);
    }

    private static String docText(List<Anno> annos) {
        if (null == annos) {
            return null;
        }
        for (Anno a : annos) {
            if ("Doc".equals(a.getName())) {
                var v = prop(a.getProperties(), "value");
                if (v instanceof String s) {
                    return s;
                }
            }
        }
        return null;
    }
}
