package com.codereview.agent.specialist.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaStructureParserTest {

    private final JavaStructureParser parser = new JavaStructureParser();

    @Test
    void extractsPackageImportsTypesMembersAndOriginalLines() {
        String source = """
                // 文件头注释
                package com.acme.order;

                import java.util.List;
                import static java.util.Objects.requireNonNull;
                import com.acme.base.*;

                /**
                 * 订单服务。
                 */
                public class OrderService extends BaseService<Order> implements Runnable, Closeable {
                    private final List<Order> orders;

                    static {
                        System.setProperty("a", "b");
                    }

                    public OrderService(List<Order> orders) {
                        this.orders = orders;
                    }

                    @Override
                    public void run() {
                        Order order = new Order();
                        System.out.println(order);
                    }

                    public List<Order> orders() {
                        return orders;
                    }

                    class Inner {
                        void inner() {
                        }
                    }
                }
                """;

        JavaSourceStructure structure = parser.parse("src/OrderService.java", source);

        assertEquals("com.acme.order", structure.packageName());
        assertEquals(3, structure.imports().size());
        assertTrue(structure.imports().stream()
                .anyMatch(item -> item.name().equals("java.util.List") && !item.wildcard()));
        assertTrue(structure.imports().stream()
                .anyMatch(item -> item.name().equals("java.util.Objects.requireNonNull")
                        && item.isStatic()));
        assertTrue(structure.imports().stream()
                .anyMatch(item -> item.name().equals("com.acme.base") && item.wildcard()));

        JavaSourceStructure.TypeDecl outer = structure.topLevelType().orElseThrow();
        assertEquals("com.acme.order.OrderService", outer.qualifiedName());
        assertEquals("class", outer.kind());
        assertEquals("BaseService<Order>", outer.extendedTypes().get(0).raw());
        assertEquals(2, outer.implementedTypes().size());
        assertEquals("List<Order>", outer.fields().get(0).type());
        assertEquals(1, outer.initializers().size());
        assertTrue(outer.initializers().get(0).isStatic());
        assertTrue(outer.methods().stream()
                .anyMatch(method -> method.name().equals("run")
                        && method.signature().contains("void run()")
                        && method.hasBody()));
        assertTrue(outer.methods().stream()
                .anyMatch(method -> method.name().equals("orders")
                        && method.returnType().equals("List<Order>")));
        assertTrue(outer.objectCreations().stream()
                .anyMatch(ref -> ref.raw().equals("Order")));

        JavaSourceStructure.TypeDecl inner = structure.type("com.acme.order.OrderService.Inner")
                .orElseThrow();
        assertEquals("com.acme.order.OrderService", inner.outerQualifiedName());
        assertTrue(inner.startLine() > outer.startLine());

        assertTrue(structure.uncoveredLines().isEmpty(),
                "所有非空行都应被类型、文件头或注释覆盖：" + structure.uncoveredLines());
        assertFalse(structure.coverage().isEmpty());
    }

    @Test
    void recordParametersBecomeFieldsAndEnumConstantsAreCovered() {
        String source = """
                package a.b;

                public record Point(int x, int y) implements Comparable<Point> {
                    public int compareTo(Point other) {
                        return 0;
                    }
                }

                enum Color {
                    RED, GREEN;
                }
                """;

        JavaSourceStructure structure = parser.parse("a/b/Point.java", source);

        JavaSourceStructure.TypeDecl point = structure.type("a.b.Point").orElseThrow();
        assertEquals("record", point.kind());
        assertEquals(2, point.fields().size());
        assertEquals("Comparable<Point>", point.implementedTypes().get(0).raw());

        JavaSourceStructure.TypeDecl color = structure.type("a.b.Color").orElseThrow();
        assertEquals("enum", color.kind());
        assertEquals(2, color.memberRanges().stream()
                .filter(range -> range.kind().equals("enum-constant")).count());
        assertTrue(structure.uncoveredLines().isEmpty());
    }

    @Test
    void parseFailureIsExplicit() {
        StructureParseException failure = assertThrows(StructureParseException.class,
                () -> parser.parse("Broken.java", "class Broken { void x( }"));
        assertTrue(failure.getMessage().contains("Broken.java"));
    }

    @Test
    void emptyFileIsNotAParsedStructure() {
        assertThrows(StructureParseException.class, () -> parser.parse("Empty.java", ""));
    }

    @Test
    void countsLinesWithoutTrailingNewlineAsOneLine() {
        assertEquals(0, JavaStructureParser.countLines(""));
        assertEquals(1, JavaStructureParser.countLines("class A {}"));
        assertEquals(1, JavaStructureParser.countLines("class A {}\n"));
        assertEquals(2, JavaStructureParser.countLines("class A {}\n\n"));
        assertNotNull(parser);
    }
}
