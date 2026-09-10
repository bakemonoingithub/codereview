package com.codereview.material;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StructureExtractorTest {

    private static final String ORDER_SERVICE = """
            package com.pdm.order;
            import com.pdm.pay.PayService;
            public class OrderService {
                private PayService payService;
                public String create(String s) { return s; }
            }
            """;

    private static final String PAY_SERVICE = """
            package com.pdm.pay;
            public class PayService {
                public void pay() {}
            }
            """;

    @Test
    void extractsClassesAndInternalEdges() {
        Material m = new StructureExtractor().extract(List.of(
                new SourceFile("OrderService.java", ORDER_SERVICE),
                new SourceFile("PayService.java", PAY_SERVICE)));

        assertEquals(2, m.classes().size());
        assertEquals("com.pdm", m.projectRootPackage());

        assertEquals(1, m.edges().size());
        assertEquals("com.pdm.order.OrderService", m.edges().get(0).from());
        assertEquals("com.pdm.pay.PayService", m.edges().get(0).to());

        ClassInfo order = m.classes().stream()
                .filter(c -> c.name().equals("OrderService")).findFirst().orElseThrow();
        assertEquals("class", order.kind());
        assertEquals("com.pdm.order", order.packageName());

        assertFalse(m.structureSummary().isBlank());
    }
}
