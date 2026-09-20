package com.codereview.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 模块级（包级）耦合聚合 —— 纯计算，无 Spring / Jackson 依赖。
 *
 * <p>口径（负责人 2026-09-20 定，别在这里"顺手优化"）：
 * <ul>
 *   <li><b>模块 = 包</b>：取类所在包相对 {@link Material#projectRootPackage()} 的**第一段**
 *       （根包 {@code com.shop} 下的 {@code com.shop.order.dao} → {@code order}）；
 *       相对路径为空（类直接落在根包）时记作 {@link #ROOT_MODULE}。</li>
 *   <li><b>模块边权重</b> = 该方向上"类级依赖条数"（去重后的 {@code (类, 类)} 依赖对数）；同模块内部依赖不计。</li>
 *   <li><b>Ca / Ce</b> = 被多少**个**模块依赖 / 依赖多少**个**模块（去重模块数，不累加权重）。</li>
 *   <li><b>I（不稳定度）</b> = {@code Ce / (Ca + Ce)}，保留 2 位小数；{@code Ca + Ce == 0} 时记 0。</li>
 *   <li><b>高耦合模块</b> = {@code Ce > 3 且 I >= 0.8}（依赖面大**且**不稳定）。</li>
 *   <li><b>模块级循环</b> = 模块图上的强连通分量（Tarjan，同 {@link DepGraph} 的做法）；二元环在文本里渲染成
 *       {@code a ↔ b}，因此"双向依赖对"不再单独用文字重复，但 JSON 里仍单列 {@link #mutualPairs()} 供前端/LLM 直读。</li>
 *   <li><b>依赖口径</b>：沿用类级边的 import-only（不含继承/反射/同包引用），界面与报告需注明。</li>
 * </ul>
 *
 * <p>输出顺序固定（便于测试断言与结果对比）：模块按 {@code high → Ce → name} 排，
 * 边按 {@code from → to} 排，环按首元素排。
 */
public final class ModuleGraph {

    /** 类直接落在根包（或根包为空）时使用的模块名。 */
    public static final String ROOT_MODULE = "(根包)";

    /** 高耦合模块口径：跨模块依赖的目标模块数下限（不含等于）。 */
    static final int HIGH_CE_THRESHOLD = 3;
    /** 高耦合模块口径：不稳定度下限（含等于）。 */
    static final double HIGH_INSTABILITY_THRESHOLD = 0.8d;

    private final List<Module> modules;
    private final List<ModuleEdge> edges;
    private final List<List<String>> cycles;
    private final List<List<String>> mutualPairs;

    private ModuleGraph(List<Module> modules, List<ModuleEdge> edges,
                        List<List<String>> cycles, List<List<String>> mutualPairs) {
        this.modules = modules;
        this.edges = edges;
        this.cycles = cycles;
        this.mutualPairs = mutualPairs;
    }

    /** 模块节点。 */
    public record Module(String name, int classCount, int ca, int ce, double instability, boolean high) {
    }

    /** 模块间依赖边，{@code weight} = 类级依赖条数。 */
    public record ModuleEdge(String from, String to, int weight) {
    }

    public static ModuleGraph of(Material material) {
        Map<String, String> moduleByFqcn = new LinkedHashMap<>();
        Map<String, Integer> classCount = new TreeMap<>();
        String root = material.projectRootPackage();
        for (ClassInfo c : material.classes()) {
            String module = moduleOf(c.packageName(), root);
            moduleByFqcn.put(c.fqcn(), module);
            classCount.merge(module, 1, Integer::sum);
        }

        // 1) 类级边 → 模块级带权边（去自环、去重依赖对）
        Set<String> moduleNodes = new LinkedHashSet<>(classCount.keySet());
        Map<String, Integer> weightByPair = new TreeMap<>();
        Set<String> classPairs = new LinkedHashSet<>();
        for (DepEdge e : material.edges()) {
            String from = moduleByFqcn.get(e.from());
            String to = moduleByFqcn.get(e.to());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            if (!classPairs.add(e.from() + " -> " + e.to())) {
                continue; // 同一对类只算一条
            }
            weightByPair.merge(from + " -> " + to, 1, Integer::sum);
        }

        // 2) Ca / Ce（去重模块数）
        Map<String, Set<String>> out = new TreeMap<>();
        Map<String, Set<String>> in = new TreeMap<>();
        for (String n : moduleNodes) {
            out.put(n, new LinkedHashSet<>());
            in.put(n, new LinkedHashSet<>());
        }
        List<ModuleEdge> edges = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : weightByPair.entrySet()) {
            String[] pair = entry.getKey().split(" -> ", 2);
            out.get(pair[0]).add(pair[1]);
            in.get(pair[1]).add(pair[0]);
            edges.add(new ModuleEdge(pair[0], pair[1], entry.getValue()));
        }
        edges.sort(Comparator.comparing(ModuleEdge::from).thenComparing(ModuleEdge::to));

        List<Module> modules = new ArrayList<>();
        for (String n : moduleNodes) {
            int ca = in.get(n).size();
            int ce = out.get(n).size();
            double instability = ca + ce == 0 ? 0d : round2((double) ce / (ca + ce));
            boolean high = ce > HIGH_CE_THRESHOLD && instability >= HIGH_INSTABILITY_THRESHOLD;
            modules.add(new Module(n, classCount.getOrDefault(n, 0), ca, ce, instability, high));
        }
        modules.sort(Comparator.comparing(Module::high).reversed()
                .thenComparing(Comparator.comparingInt(Module::ce).reversed())
                .thenComparing(Module::name));

        // 3) 模块级循环（SCC）与双向依赖对
        List<List<String>> cycles = cyclesOf(moduleNodes, out);
        List<List<String>> mutualPairs = new ArrayList<>();
        for (ModuleEdge e : edges) {
            if (weightByPair.containsKey(e.to() + " -> " + e.from()) && e.from().compareTo(e.to()) < 0) {
                mutualPairs.add(List.of(e.from(), e.to()));
            }
        }
        mutualPairs.sort(Comparator.comparing((List<String> p) -> p.get(0)));

        return new ModuleGraph(modules, edges, cycles, mutualPairs);
    }

    public List<Module> modules() {
        return modules;
    }

    public List<ModuleEdge> edges() {
        return edges;
    }

    public List<List<String>> cycles() {
        return cycles;
    }

    public List<List<String>> mutualPairs() {
        return mutualPairs;
    }

    /** 跨模块依赖的类级条数合计（= 各模块边权重之和）。 */
    public int dependencyCount() {
        int sum = 0;
        for (ModuleEdge e : edges) {
            sum += e.weight();
        }
        return sum;
    }

    public List<String> highModules() {
        List<String> result = new ArrayList<>();
        for (Module m : modules) {
            if (m.high()) {
                result.add(m.name());
            }
        }
        return result;
    }

    /**
     * 模块级确定性结论（一段话，给报告与界面直读；不受 LLM 影响）。
     * 单模块项目明确写"不适用"，避免报告该节没有确定性内容可写。
     */
    public String summary() {
        if (modules.isEmpty()) {
            return "无模块数据（未解析到任何类）";
        }
        if (modules.size() == 1) {
            Module only = modules.get(0);
            return String.format("模块 1 个（%s，%d 个类）；模块级结论不适用（项目只有一个包，无跨模块依赖与循环）",
                    only.name(), only.classCount());
        }
        List<String> highs = highModules();
        return String.format("模块 %d 个；跨模块依赖 %d 对、共 %d 处类级依赖；高耦合模块 %s；模块级循环 %s",
                modules.size(), edges.size(), dependencyCount(),
                highs.isEmpty() ? "无" : highs.size() + " 个（" + String.join("、", highs) + "）",
                cycles.isEmpty() ? "无" : cycles.size() + " 组（" + renderCycles() + "）");
    }

    private String renderCycles() {
        List<String> rendered = new ArrayList<>();
        for (List<String> cycle : cycles) {
            if (cycle.size() == 2) {
                rendered.add(cycle.get(0) + " ↔ " + cycle.get(1));
            } else {
                rendered.add(String.join(" → ", cycle) + " → " + cycle.get(0));
            }
        }
        return String.join("；", rendered);
    }

    /** 包名 → 模块名：取相对根包的第一段；为空则 {@link #ROOT_MODULE}。 */
    static String moduleOf(String packageName, String rootPackage) {
        String relative = relativeToRoot(packageName, rootPackage);
        if (relative.isEmpty()) {
            return ROOT_MODULE;
        }
        int dot = relative.indexOf('.');
        return dot < 0 ? relative : relative.substring(0, dot);
    }

    private static String relativeToRoot(String packageName, String rootPackage) {
        String pkg = packageName == null ? "" : packageName.trim();
        String root = rootPackage == null ? "" : rootPackage.trim();
        if (pkg.isEmpty()) {
            return "";
        }
        if (root.isEmpty()) {
            return pkg;
        }
        if (pkg.equals(root)) {
            return "";
        }
        return pkg.startsWith(root + ".") ? pkg.substring(root.length() + 1) : pkg;
    }

    /** 模块图上的 Tarjan SCC，返回节点数 > 1 的分量（与 DepGraph 同一套算法）。 */
    private static List<List<String>> cyclesOf(Set<String> nodes, Map<String, Set<String>> out) {
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<String, Integer> low = new LinkedHashMap<>();
        Map<String, Boolean> onStack = new LinkedHashMap<>();
        List<String> stack = new ArrayList<>();
        List<List<String>> comps = new ArrayList<>();
        int[] counter = {0};
        for (String n : nodes) {
            if (!index.containsKey(n)) {
                tarjan(n, index, low, onStack, stack, comps, counter, out);
            }
        }
        List<List<String>> cycles = new ArrayList<>();
        for (List<String> comp : comps) {
            if (comp.size() > 1) {
                comp.sort(Comparator.naturalOrder());
                cycles.add(comp);
            }
        }
        cycles.sort(Comparator.comparing((List<String> c) -> c.get(0)));
        return cycles;
    }

    private static void tarjan(String v, Map<String, Integer> index, Map<String, Integer> low,
                               Map<String, Boolean> onStack, List<String> stack, List<List<String>> comps,
                               int[] counter, Map<String, Set<String>> out) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.add(v);
        onStack.put(v, true);
        for (String w : out.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                tarjan(w, index, low, onStack, stack, comps, counter, out);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (Boolean.TRUE.equals(onStack.get(w))) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> comp = new ArrayList<>();
            String w;
            do {
                w = stack.remove(stack.size() - 1);
                onStack.put(w, false);
                comp.add(w);
            } while (!w.equals(v));
            comps.add(comp);
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
