package com.codereview.material;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块级（包级）耦合聚合的口径测试（T-03）。
 *
 * <p>锁住的都是"报告与界面会照着念"的确定性结论：模块名怎么切、边权是什么、Ca/Ce 是不是去重模块数、
 * 什么算高耦合模块、模块级循环怎么识别、单包项目怎么写结论。
 */
class ModuleGraphTest {

    private static final String ROOT = "com.shop";

    @Test
    void aggregatesClassEdgesIntoWeightedModuleEdges() {
        Material material = material(ROOT,
                List.of(cls("com.shop.order", "OrderService"), cls("com.shop.order", "OrderRepo"),
                        cls("com.shop.inventory", "StockDao")),
                List.of(edge("com.shop.order.OrderService", "com.shop.inventory.StockDao"),
                        edge("com.shop.order.OrderRepo", "com.shop.inventory.StockDao")));

        ModuleGraph g = ModuleGraph.of(material);

        assertEquals(List.of("order", "inventory"), names(g));
        assertEquals(1, g.edges().size(), "两个类指向同一个模块，只应聚合成一条模块边");
        assertEquals("order", g.edges().get(0).from());
        assertEquals("inventory", g.edges().get(0).to());
        assertEquals(2, g.edges().get(0).weight(), "权重 = 类级依赖条数");
        assertEquals(2, g.dependencyCount());

        ModuleGraph.Module order = module(g, "order");
        assertEquals(2, order.classCount());
        assertEquals(0, order.ca());
        assertEquals(1, order.ce(), "Ce 是「依赖多少个模块」，不是权重之和");
        assertEquals(1.0d, order.instability());
        ModuleGraph.Module inventory = module(g, "inventory");
        assertEquals(1, inventory.ca());
        assertEquals(0, inventory.ce());
        assertEquals(0.0d, inventory.instability());
    }

    @Test
    void ignoresSameModuleEdgesAndSelfLoops() {
        Material material = material(ROOT,
                List.of(cls("com.shop.order", "A"), cls("com.shop.order", "B")),
                List.of(edge("com.shop.order.A", "com.shop.order.B"),
                        edge("com.shop.order.A", "com.shop.order.A")));

        ModuleGraph g = ModuleGraph.of(material);

        assertEquals(1, g.modules().size());
        assertTrue(g.edges().isEmpty(), "同模块内部依赖不进模块图");
        assertEquals(0, g.dependencyCount());
    }

    @Test
    void moduleNameIsFirstSegmentBelowRootPackage() {
        Material material = material(ROOT,
                List.of(cls("com.shop.order.dao", "OrderDao"), cls("com.shop.order.service", "OrderService"),
                        cls("com.shop.web", "OrderController")),
                List.of(edge("com.shop.order.service.OrderService", "com.shop.order.dao.OrderDao")));

        ModuleGraph g = ModuleGraph.of(material);

        assertEquals(List.of("order", "web"), names(g), "order.dao / order.service 都归到 order");
        assertTrue(g.edges().isEmpty(), "同属 order 模块的依赖被消掉");
    }

    @Test
    void classesDirectlyInRootPackageFallIntoRootModule() {
        Material material = material(ROOT,
                List.of(cls("com.shop", "Bootstrap"), cls("com.shop.web", "Api")),
                List.of(edge("com.shop.Bootstrap", "com.shop.web.Api")));

        ModuleGraph g = ModuleGraph.of(material);

        assertEquals(List.of(ModuleGraph.ROOT_MODULE, "web"), names(g));
        assertEquals(1, module(g, ModuleGraph.ROOT_MODULE).classCount());
    }

    @Test
    void highCouplingRequiresBothLargeCeAndHighInstability() {
        // web：Ce=4、Ca=0 → I=1.00 且 Ce>3 ⇒ 高耦合
        // order：Ce=1、Ca=0 → I=1.00 但 Ce 不够 ⇒ 不算
        // common：Ce=1、Ca=4 → I=0.20、Ce 也不够 ⇒ 不算
        Material material = material(ROOT,
                List.of(cls("com.shop.web", "Web"), cls("com.shop.order", "Order"),
                        cls("com.shop.common", "Common"), cls("com.shop.inventory", "Inventory"),
                        cls("com.shop.billing", "Billing")),
                List.of(edge("com.shop.web.Web", "com.shop.order.Order"),
                        edge("com.shop.web.Web", "com.shop.common.Common"),
                        edge("com.shop.web.Web", "com.shop.inventory.Inventory"),
                        edge("com.shop.web.Web", "com.shop.billing.Billing"),
                        edge("com.shop.order.Order", "com.shop.common.Common")));

        ModuleGraph g = ModuleGraph.of(material);

        assertTrue(module(g, "web").high(), "Ce=4 且 I=1.00 应判为高耦合模块");
        assertFalse(module(g, "order").high(), "只有 1 个下游模块，不算高耦合");
        assertFalse(module(g, "common").high(), "被依赖多、不稳定度低，不算高耦合");
        assertEquals(List.of("web"), g.highModules());
        assertEquals("web", g.modules().get(0).name(), "高耦合模块排在最前");
        assertTrue(g.summary().contains("高耦合模块 1 个（web）"), g.summary());
    }

    @Test
    void detectsModuleLevelCyclesAndMutualPairs() {
        Material material = material(ROOT,
                List.of(cls("com.shop.order", "Order"), cls("com.shop.inventory", "Inventory"),
                        cls("com.shop.billing", "Billing")),
                List.of(edge("com.shop.order.Order", "com.shop.inventory.Inventory"),
                        edge("com.shop.inventory.Inventory", "com.shop.order.Order"),
                        edge("com.shop.inventory.Inventory", "com.shop.billing.Billing"),
                        edge("com.shop.billing.Billing", "com.shop.order.Order")));

        ModuleGraph g = ModuleGraph.of(material);

        assertEquals(1, g.cycles().size(), "三个模块互相成环，应是一个 3 元 SCC");
        assertEquals(List.of("billing", "inventory", "order"), g.cycles().get(0));
        assertEquals(List.of(List.of("inventory", "order")), g.mutualPairs(), "order ↔ inventory 是其中的双向对");
        assertTrue(g.summary().contains("模块级循环 1 组"), g.summary());
        assertTrue(g.summary().contains("billing → inventory → order → billing"), g.summary());
    }

    @Test
    void twoModuleCycleRendersAsMutualPair() {
        Material material = material(ROOT,
                List.of(cls("com.shop.order", "Order"), cls("com.shop.inventory", "Inventory")),
                List.of(edge("com.shop.order.Order", "com.shop.inventory.Inventory"),
                        edge("com.shop.inventory.Inventory", "com.shop.order.Order")));

        ModuleGraph g = ModuleGraph.of(material);

        assertEquals(List.of(List.of("inventory", "order")), g.cycles());
        assertTrue(g.summary().contains("inventory ↔ order"), g.summary());
    }

    @Test
    void singleModuleProjectSaysNotApplicable() {
        Material material = material("p",
                List.of(cls("p", "A"), cls("p", "B")),
                List.of(edge("p.A", "p.B")));

        ModuleGraph g = ModuleGraph.of(material);

        assertEquals(1, g.modules().size());
        assertEquals(ModuleGraph.ROOT_MODULE, g.modules().get(0).name());
        assertTrue(g.edges().isEmpty());
        assertTrue(g.summary().contains("模块级结论不适用"), g.summary());
    }

    @Test
    void emptyMaterialProducesExplicitConclusion() {
        ModuleGraph g = ModuleGraph.of(material(ROOT, List.of(), List.of()));

        assertTrue(g.modules().isEmpty());
        assertEquals("无模块数据（未解析到任何类）", g.summary());
    }

    @Test
    void outputOrderIsStable() {
        // 两个 Ce 相同的模块按名字排；高耦合模块永远在最前
        Material material = material(ROOT,
                List.of(cls("com.shop.zeta", "Z"), cls("com.shop.alpha", "A"), cls("com.shop.big", "B"),
                        cls("com.shop.t1", "T1"), cls("com.shop.t2", "T2"), cls("com.shop.t3", "T3"),
                        cls("com.shop.t4", "T4")),
                List.of(edge("com.shop.zeta.Z", "com.shop.alpha.A"),
                        edge("com.shop.big.B", "com.shop.alpha.A"),
                        edge("com.shop.big.B", "com.shop.zeta.Z"),
                        edge("com.shop.big.B", "com.shop.t1.T1"),
                        edge("com.shop.big.B", "com.shop.t2.T2"),
                        edge("com.shop.big.B", "com.shop.t3.T3"),
                        edge("com.shop.big.B", "com.shop.t4.T4")));

        ModuleGraph g = ModuleGraph.of(material);

        assertEquals("big", g.modules().get(0).name(), "高耦合（Ce=6）排最前");
        assertEquals(List.of("big", "zeta", "alpha", "t1", "t2", "t3", "t4"), names(g),
                "高耦合优先 → Ce 降序 → 名字升序");
        for (int i = 1; i < g.edges().size(); i++) {
            String prev = g.edges().get(i - 1).from() + "->" + g.edges().get(i - 1).to();
            String curr = g.edges().get(i).from() + "->" + g.edges().get(i).to();
            assertTrue(prev.compareTo(curr) < 0, "模块边按 from→to 排序：" + prev + " vs " + curr);
        }
    }

    // ---------- helpers ----------

    private static List<String> names(ModuleGraph g) {
        List<String> result = new ArrayList<>();
        for (ModuleGraph.Module m : g.modules()) {
            result.add(m.name());
        }
        return result;
    }

    private static ModuleGraph.Module module(ModuleGraph g, String name) {
        return g.modules().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static ClassInfo cls(String packageName, String name) {
        return new ClassInfo(name + ".java", packageName, name, "class",
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static DepEdge edge(String from, String to) {
        return new DepEdge(from, to);
    }

    private static Material material(String rootPackage, List<ClassInfo> classes, List<DepEdge> edges) {
        return new Material(rootPackage, classes, edges, "");
    }
}
