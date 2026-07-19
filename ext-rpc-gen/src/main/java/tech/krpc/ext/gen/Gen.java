/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.ext.gen;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.common.MethodStub;
import tech.krpc.ext.gen.meta.Api;
import tech.krpc.ext.gen.meta.ApiMetaRoot;
import tech.krpc.ext.gen.meta.Dto;
import tech.krpc.ext.gen.meta.Property;
import tech.krpc.ext.gen.meta.PropertyType;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.RpcServerBuilder.RpcMetaMethod;
import tech.krpc.util.JsonUtils;
import tech.krpc.util.RefUtils;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 4:32 PM
 */
public class Gen {

    private static final Logger LOG = LoggerFactory.getLogger(Gen.class);


    public static String basePkg ="com.zlkj";

    //static final String FC = File.separator;

    static final String[] CLS_FOLDERS ={ "/out/test/classes/","/build/classes/java/"};

    public static File defaultFolder(LangEnum lan){
        var url = Gen.class.getResource("/");
        if(null == url){
            return null;
        }
        var path = url.getPath();
        for (var suf : CLS_FOLDERS) {
            int i;
            if ( (i =path.lastIndexOf(suf)) > 0) {
                var f = new File(path.substring(0, i));
                var subLang = new File(f, lan.name().toLowerCase(Locale.US));
                System.out.println("use folder : " + subLang);
                subLang.mkdir();
                return subLang;
            }
        }
        System.out.println("unknow path : "+ path);
        return null;
    }

    public static void genTypescript(String appName){
        genTypescript(appName,defaultFolder(LangEnum.Typescript));
    }

    public static void genSingleTypescript(String appName,Class singleRpc) {
        try {
            genApiMetaRoot(scanSingle(appName,singleRpc),LangEnum.Typescript,defaultFolder(LangEnum.Typescript));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public static void genMiniprogram(String appName){
        gen(appName,LangEnum.Miniprogram,defaultFolder(LangEnum.Miniprogram));
    }

    public static void genDart(String appName){
        genDart(appName,defaultFolder(LangEnum.Dart));
    }

    public static void genYamltest(String appName){
        try {
            gen(appName,LangEnum.Yamltest,defaultFolder(LangEnum.Yamltest));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void genTypescript(String appName, File outFolder) {
        try {
            gen(appName,LangEnum.Typescript,outFolder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void genDart(String appName, File outFolder){
        try {
            gen(appName,LangEnum.Dart,outFolder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }



    static void gen(String appName, LangEnum template, File outFolder) {

        //Set<ClassInfo> classesInPackage = ClassPath.from(cl).getTopLevelClassesRecursive("com.zlkj");
        //classesInPackage.forEach(it->
        //
        //        System.out.println(it.load()));
        ApiMetaRoot metas;
        try {
            metas = scan(appName, basePkg);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        genApiMetaRoot(metas,template,outFolder);
    }
    static void genApiMetaRoot( ApiMetaRoot metas,LangEnum template,File outFolder) {

        /* Create and adjust the configuration singleton */
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setClassForTemplateLoading(Gen.class,"/");
        //cfg.setDirectoryForTemplateLoading(new File("/where/you/store/templates"));
        // Recommended settings for new projects:
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);

        /* Create a data-model */
        var root = new HashMap<String,Object>();
        root.put("app", metas.getApp());
        root.put("lang", template.name());
        var dtos = metas.getDtos().stream().filter(Dto::hasChild).collect(Collectors.toList());
        //JSON dtos;
        dtos.forEach(template.remapping::remapping);
        // GENDET-002: topological (referenced-before-referencing) DTO emission order,
        // with the GENDET-001 alphabetical (name, originName) key as the deterministic
        // tie-break. Alphabetical order alone is deterministic but NOT dependency-safe:
        // in single-file TS output built with `emitDecoratorMetadata`, a DTO whose field
        // (or generic type argument / array element) references another generated DTO that
        // sorts AFTER it emits a runtime forward reference (`Cannot access 'X' before
        // initialization` — TDZ). See topoOrderDtos. Sort AFTER remapping so getName() is final.
        var orderedDtos = topoOrderDtos(dtos);
        root.put("dtos", orderedDtos);

        Template dtoTemp ;
        try {
            dtoTemp = cfg.getTemplate(template.dto);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var dtoFileName = template.dtoFileName(metas.getApp());

        root.put("dtoFile",dtoFileName );
        System.out.println("---------- gen to : "+ outFolder);
        System.out.println("---------- gen : "+ dtoFileName);

        try {
            dtoTemp.process(root, toWriter(outFolder,dtoFileName));
            Template serviceTemp = cfg.getTemplate(template.serive);
            // GENDET-001: deterministic service-file emission order. Upstream `apis` come
            // from Collectors.groupingBy (HashMap) in buildApiMeta. Primary key = service
            // name; secondary key = description (null-safe) so two services whose emitted
            // (prefix-stripped, lower-cased) filename would collide still order — and thus
            // last-write to a shared path resolves — deterministically instead of retaining
            // input order. (Distinct gRPC services carry distinct registered names in practice.)
            var apis = new ArrayList<>(metas.getApis());
            apis.sort(Comparator.comparing(Api::getName)
                    .thenComparing(Api::getDescription, Comparator.nullsFirst(Comparator.naturalOrder())));
            for (var api : apis) {
                root.put("service",api );
                api.getMethods().forEach(m->{
                    template.remapping.remapping(m.getArg());
                    template.remapping.remapping(m.getRes());
                });
                Collections.sort(api.getMethods());
                var serviceFile = template.serviceFileName(api.getName());
                root.put("serviceFile",serviceFile );
                System.out.println("---------- gen : "+ serviceFile);
                serviceTemp.process(root, toWriter(outFolder,serviceFile));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * GENDET-002: order the (already remapped) generated DTOs so that a DTO is emitted AFTER
     * every generated DTO it references — a topological sort over the condensation of the
     * dependency graph, drained through an alphabetically-ordered ready-set.
     *
     * <p>Dependency edges run from a referencing DTO to each generated DTO of THIS run that
     * appears in its emitted output: a field type, a generic type argument (e.g. {@code
     * List<Zebra>}, the {@code K}/{@code V} of {@code Map<K,V>}) or an array element type
     * ({@code Zebra[]}). Nodes are matched by post-remap simple name, not object identity: the
     * meta model is JSON round-tripped (Jackson, no {@code @JsonIdentityInfo}), so a field's
     * {@code rawType} is a DISTINCT {@link Dto} instance from the top-level node of that name.
     *
     * <p>Determinism: nodes are totally ordered by {@code name}, then {@code originName}, then a
     * content fingerprint (a stable JSON serialization), then a stable index. The fingerprint
     * makes the order independent of the upstream input order even when two nodes tie on
     * {@code (name, originName)}; the index is the final guard, and two nodes tying on all four
     * are byte-identical DTOs whose relative order cannot change the emitted text. (Production
     * dedupes DTO simple names upstream, so such ties are already pathological.)
     *
     * <p>Cycles never fail generation. The graph is condensed into strongly-connected components
     * (Tarjan): a DTO merely DOWNSTREAM of a cycle is a singleton SCC and keeps its topological
     * placement, while a genuine multi-node SCC (a real cycle) is emitted alphabetically within
     * its condensation slot and a WARN names ONLY its members. The earlier "append the whole
     * residual alphabetically" fallback was wrong: it reordered downstream nodes ahead of their
     * dependencies (Round 2, finding 1). The consumer-side forward-reference (TDZ) risk that
     * remains for a true cycle is a DTO-contract smell, not a generation failure.
     *
     * <p>Superclass edges are intentionally absent: the meta model carries no superclass
     * reference and no DTO template emits an {@code extends} clause, so a superclass forward
     * reference cannot occur in emitted output (inheritance is flattened upstream).
     */
    static List<Dto> topoOrderDtos(List<Dto> dtos) {
        if (dtos.size() <= 1) {
            return dtos;
        }
        // GENDET-001 base order first, so the stable index below reflects alphabetical rank.
        dtos.sort(Comparator.comparing(Dto::getName)
                .thenComparing(Dto::getOriginName, Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<Dto, Integer> idx = new IdentityHashMap<>();
        Map<Dto, String> fingerprint = new IdentityHashMap<>();
        for (int i = 0; i < dtos.size(); i++) {
            Dto d = dtos.get(i);
            idx.put(d, i);
            fingerprint.put(d, JsonUtils.stringify(d));
        }
        Comparator<Dto> order = Comparator.comparing(Dto::getName)
                .thenComparing(Dto::getOriginName, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(fingerprint::get)
                .thenComparing(idx::get);

        // Post-remap name -> node(s). A duplicated post-remap name links an edge to every
        // node carrying it (pathological but tolerated deterministically).
        Map<String, List<Dto>> byName = new HashMap<>();
        for (Dto d : dtos) {
            byName.computeIfAbsent(d.getName(), k -> new ArrayList<>()).add(d);
        }
        // Dependency edges: dep -> dependent (dep must be emitted before dependent).
        Map<Dto, List<Dto>> successors = new IdentityHashMap<>();
        for (Dto d : dtos) {
            successors.put(d, new ArrayList<>());
        }
        for (Dto d : dtos) {
            Set<Dto> deps = Collections.newSetFromMap(new IdentityHashMap<>());
            collectDtoRefs(d, byName, deps);
            deps.remove(d); // a self-reference is legal (TS/Dart) and never a TDZ
            for (Dto dep : deps) {
                successors.get(dep).add(d);
            }
        }

        // Condense SCCs, then topo-sort the condensation DAG with an ordered ready-set.
        List<List<Dto>> sccs = stronglyConnectedComponents(dtos, successors);
        Map<Dto, Integer> sccId = new IdentityHashMap<>();
        for (int i = 0; i < sccs.size(); i++) {
            for (Dto d : sccs.get(i)) {
                sccId.put(d, i);
            }
        }
        List<Set<Integer>> condSucc = new ArrayList<>();
        int[] condIndeg = new int[sccs.size()];
        List<Dto> sccKey = new ArrayList<>(); // each SCC's alphabetically-min member (ready key)
        for (int i = 0; i < sccs.size(); i++) {
            condSucc.add(new HashSet<>());
            sccKey.add(sccs.get(i).stream().min(order).orElseThrow());
        }
        for (Dto d : dtos) {
            int from = sccId.get(d);
            for (Dto s : successors.get(d)) {
                int to = sccId.get(s);
                if (from != to && condSucc.get(from).add(to)) {
                    condIndeg[to]++;
                }
            }
        }
        Comparator<Integer> sccOrder = Comparator.comparing((Integer i) -> sccKey.get(i), order);
        PriorityQueue<Integer> ready = new PriorityQueue<>(sccOrder);
        for (int i = 0; i < sccs.size(); i++) {
            if (condIndeg[i] == 0) {
                ready.add(i);
            }
        }
        List<Dto> ordered = new ArrayList<>(dtos.size());
        while (!ready.isEmpty()) {
            int i = ready.poll();
            List<Dto> members = new ArrayList<>(sccs.get(i));
            members.sort(order);
            ordered.addAll(members);
            if (members.size() > 1) {
                // One WARN per genuine cycle (multi-node SCC), naming that SCC's members — so
                // independent cycles stay distinct and grouping is not lost. Emitted in the
                // condensation's deterministic drain order.
                LOG.warn("GENDET-002: emitting {} DTO(s) that form a dependency cycle in "
                        + "alphabetical order — consumer-side forward-reference (TDZ) risk remains "
                        + "for this cycle; it is a DTO-contract smell, not a generation failure: {}",
                        members.size(),
                        members.stream().map(Dto::getName).collect(Collectors.joining(", ")));
            }
            for (int j : condSucc.get(i)) {
                if (--condIndeg[j] == 0) {
                    ready.add(j);
                }
            }
        }
        return ordered;
    }

    /**
     * Tarjan's strongly-connected-components, iterative (no recursion-depth limit on deep DTO
     * graphs). Returns one member list per SCC. SCC membership is invariant of traversal order,
     * so the caller's deterministic ordering of the condensation makes the overall result stable.
     */
    private static List<List<Dto>> stronglyConnectedComponents(List<Dto> dtos,
            Map<Dto, List<Dto>> successors) {
        Map<Dto, Integer> index = new IdentityHashMap<>();
        Map<Dto, Integer> low = new IdentityHashMap<>();
        Set<Dto> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Dto> stack = new ArrayDeque<>();
        List<List<Dto>> sccs = new ArrayList<>();
        int[] counter = {0};
        for (Dto root : dtos) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<Dto> callNodes = new ArrayDeque<>();
            Deque<Iterator<Dto>> callIters = new ArrayDeque<>();
            index.put(root, counter[0]);
            low.put(root, counter[0]);
            counter[0]++;
            stack.push(root);
            onStack.add(root);
            callNodes.push(root);
            callIters.push(successors.get(root).iterator());
            while (!callNodes.isEmpty()) {
                Dto v = callNodes.peek();
                Iterator<Dto> it = callIters.peek();
                boolean descended = false;
                while (it.hasNext()) {
                    Dto w = it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]);
                        counter[0]++;
                        stack.push(w);
                        onStack.add(w);
                        callNodes.push(w);
                        callIters.push(successors.get(w).iterator());
                        descended = true;
                        break;
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                }
                if (descended) {
                    continue;
                }
                if (low.get(v).intValue() == index.get(v).intValue()) {
                    List<Dto> scc = new ArrayList<>();
                    Dto w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (w != v);
                    sccs.add(scc);
                }
                callNodes.pop();
                callIters.pop();
                if (!callNodes.isEmpty()) {
                    Dto parent = callNodes.peek();
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
            }
        }
        return sccs;
    }

    /** Collect the generated DTOs referenced by {@code d}'s field types (recursing generics). */
    static void collectDtoRefs(Dto d, Map<String, List<Dto>> byName, Set<Dto> deps) {
        if (!d.hasChild()) {
            return;
        }
        for (Property f : d.getFields()) {
            if (f != null) {
                collectTypeRefs(f.getType(), byName, deps);
            }
        }
    }

    /** Add the referenced generated DTO(s) for a type and, recursively, its generic arguments. */
    static void collectTypeRefs(PropertyType t, Map<String, List<Dto>> byName, Set<Dto> deps) {
        if (t == null) {
            return;
        }
        Dto raw = t.getRawType();
        if (raw != null && raw.getName() != null) {
            addRefs(raw.getName(), byName, deps);
            // Concrete array element type (e.g. `Zebra[]`, `Zebra[][]`) — the meta-model analogue
            // of Class.isArray(): a concrete array is a raw type whose simple name carries `[]`
            // suffixes (NOT a GenericArrayType, which upstream models as List + a generic arg).
            // Its element is a real emitted reference and the same TDZ class as a plain field, so
            // strip the suffix and add the element edge too (Round 2, finding 2).
            String base = raw.getName();
            while (base.endsWith("[]")) {
                base = base.substring(0, base.length() - 2);
            }
            if (!base.equals(raw.getName())) {
                addRefs(base, byName, deps);
            }
        }
        if (t.getGenerics() != null) {
            for (PropertyType g : t.getGenerics()) {
                collectTypeRefs(g, byName, deps);
            }
        }
    }

    private static void addRefs(String name, Map<String, List<Dto>> byName, Set<Dto> deps) {
        List<Dto> targets = byName.get(name);
        if (targets != null) {
            deps.addAll(targets);
        }
    }

    static Writer toWriter(File outFolder,String fileName) throws IOException {
        if(null == outFolder){
            return new OutputStreamWriter(System.out);
        }
        var out = new File(outFolder,fileName);
        //out.getParentFile().mkdirs();
        return new FileWriter(out, StandardCharsets.UTF_8);
    }

    static ApiMetaRoot scan(String appName , String pkg) throws IOException {
        Set<ClassInfo> classesInPackage = ClassPath.from(Thread.currentThread().getContextClassLoader())
                .getTopLevelClassesRecursive(pkg);

        var metaMethods = new ArrayList<RpcMetaMethod>();
        for(var ci : classesInPackage){
            var clz  = ci.load();

            if(clz.isInterface() && clz.isAnnotationPresent(UnsafeWeb.class)){
                var rpcAnno = clz.getAnnotation(RpcService.class);
                System.out.println("---------- Found RpcService : " + clz.getName() );
                for(MethodStub stub : RefUtils.toRpcMethods(appName,clz)){
                    metaMethods.add(RpcServerBuilder.toMeta(stub,rpcAnno));
                }
            }
        }

        var api = RpcServerBuilder.buildApiMeta(metaMethods);
        api.setApp(appName);
        var json = JsonUtils.stringify(api);

        return JsonUtils.parse(json,ApiMetaRoot.class);
    }

    static ApiMetaRoot scanSingle(String appName , Class clz) throws IOException {
        var metaMethods = new ArrayList<RpcMetaMethod>();
        if(clz.isInterface()){
            var rpcAnno = (RpcService)clz.getAnnotation(RpcService.class);
            System.out.println("---------- Found RpcService : " + clz.getName() );
            for(MethodStub stub : RefUtils.toRpcMethods(appName,clz)){
                metaMethods.add(RpcServerBuilder.toMeta(stub,rpcAnno));
            }
        }
        var api = RpcServerBuilder.buildApiMeta(metaMethods);
        api.setApp(appName);
        var json = JsonUtils.stringify(api);

        return JsonUtils.parse(json,ApiMetaRoot.class);
    }
}