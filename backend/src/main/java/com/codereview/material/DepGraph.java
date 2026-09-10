package com.codereview.material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依赖图确定性计算：扇入/扇出、强连通分量（循环依赖组）、高耦合标记。
 */
public final class DepGraph {

    private final List<String> nodes;
    private final Map<String, Set<String>> outgoing = new HashMap<>();
    private final Map<String, Set<String>> incoming = new HashMap<>();

    public DepGraph(Material material) {
        Set<String> known = new LinkedHashSet<>();
        for (ClassInfo c : material.classes()) {
            known.add(c.fqcn());
        }
        nodes = new ArrayList<>(known);
        for (String n : nodes) {
            outgoing.put(n, new LinkedHashSet<>());
            incoming.put(n, new LinkedHashSet<>());
        }
        for (DepEdge e : material.edges()) {
            if (known.contains(e.from()) && known.contains(e.to()) && !e.from().equals(e.to())) {
                outgoing.get(e.from()).add(e.to());
                incoming.get(e.to()).add(e.from());
            }
        }
    }

    public List<String> nodes() {
        return nodes;
    }

    public int fanOut(String fqcn) {
        return outgoing.getOrDefault(fqcn, Set.of()).size();
    }

    public int fanIn(String fqcn) {
        return incoming.getOrDefault(fqcn, Set.of()).size();
    }

    /** 强连通分量（Tarjan），返回节点数 > 1 的分量 = 循环依赖组。 */
    public List<List<String>> findCycles() {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, Boolean> onStack = new HashMap<>();
        List<String> stack = new ArrayList<>();
        List<List<String>> sccs = new ArrayList<>();
        int[] counter = {0};
        for (String n : nodes) {
            if (!index.containsKey(n)) {
                tarjan(n, index, low, onStack, stack, sccs, counter);
            }
        }
        List<List<String>> cycles = new ArrayList<>();
        for (List<String> scc : sccs) {
            if (scc.size() > 1) {
                cycles.add(scc);
            }
        }
        return cycles;
    }

    private void tarjan(String v, Map<String, Integer> index, Map<String, Integer> low, Map<String, Boolean> onStack,
                        List<String> stack, List<List<String>> sccs, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.add(v);
        onStack.put(v, true);
        for (String w : outgoing.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                tarjan(w, index, low, onStack, stack, sccs, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (Boolean.TRUE.equals(onStack.get(w))) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> scc = new ArrayList<>();
            String w;
            do {
                w = stack.remove(stack.size() - 1);
                onStack.put(w, false);
                scc.add(w);
            } while (!w.equals(v));
            sccs.add(scc);
        }
    }

    /** 高耦合：扇出超过阈值的类（按扇出降序）。 */
    public List<String> highCoupling(int threshold) {
        List<String> result = new ArrayList<>();
        for (String n : nodes) {
            if (fanOut(n) > threshold) {
                result.add(n);
            }
        }
        result.sort((a, b) -> Integer.compare(fanOut(b), fanOut(a)));
        return result;
    }
}
