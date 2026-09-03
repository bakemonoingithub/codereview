package com.codereview.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JavaParser 实验断言：三类断言覆盖实验要回答的核心问题。
 * <ol>
 *   <li>抽取正确性（对已知 fixture 精确断言）；</li>
 *   <li>Java 17 新语法（record / sealed 合成 fixture）；</li>
 *   <li>真实项目解析成功率（需 {@code -Dprobe.root=...} 指向真实代码目录）。</li>
 * </ol>
 */
class JavaParserProbeTest {

    private static final String ROOT = "org.example.anisonmanage";

    // ------------------------------------------------------------------
    // 1. 抽取正确性
    // ------------------------------------------------------------------

    @Test
    void extractLombokEntity() {
        String src = """
                package org.example.anisonmanage.entity;

                import jakarta.persistence.*;
                import lombok.Getter;
                import lombok.Setter;

                import java.util.List;

                @Entity
                @Table(name = "songs")
                @Getter
                @Setter
                public class Song {
                    @Id
                    private Long id;
                    private String title;
                    private List<LyricLine> lyrics;
                }
                """;
        JavaParserProbe.TypeSummary t = onlyType(src, "Song.java");

        assertEquals("class", t.kind());
        assertEquals("Song", t.name());
        assertTrue(t.annotations().contains("Getter"), "Lombok @Getter 应在 AST 可见");
        assertTrue(t.annotations().contains("Setter"), "Lombok @Setter 应在 AST 可见");
        assertTrue(t.annotations().contains("Entity"));

        assertEquals(3, t.fields().size(), "3 个显式字段应被抽出");
        assertTrue(t.fields().stream().anyMatch(f -> f.name().equals("id") && f.type().equals("Long")));
        assertTrue(t.fields().stream().anyMatch(f -> f.name().equals("title") && f.type().equals("String")));

        // 关键结论：Lombok 生成的 getter/setter 不出现在源码 AST 中
        assertEquals(0, t.methods().size(), "Lombok 生成方法不在 AST 中，方法列表应为空");
    }

    @Test
    void extractController() {
        String src = """
                package org.example.anisonmanage.controller;

                import org.example.anisonmanage.dto.SongDTO;
                import org.example.anisonmanage.pojo.Result;
                import org.example.anisonmanage.service.SongService;
                import org.springframework.web.bind.annotation.*;

                import java.util.List;

                @RestController
                @RequestMapping("/api/song")
                public class SongController {
                    @PostMapping
                    public Result add(SongDTO song){ return null; }

                    @PutMapping("/{id}")
                    public Result update(Long id, SongDTO song){ return null; }

                    @DeleteMapping("/{id}")
                    public Result delete(Long id){ return null; }

                    @DeleteMapping("/batch")
                    public Result batchDelete(List<Long> ids){ return null; }
                }
                """;
        CompilationUnit cu = JavaParserProbe.parse(src);
        JavaParserProbe.FileSummary fs = JavaParserProbe.summarize(cu, "SongController.java", ROOT);
        JavaParserProbe.TypeSummary t = fs.types().get(0);

        assertEquals("class", t.kind());
        assertEquals("SongController", t.name());
        assertEquals(4, t.methods().size(), "4 个公共方法应被抽出");
        assertTrue(t.methods().contains("Result add(SongDTO)"));
        assertTrue(t.methods().contains("Result update(Long, SongDTO)"));
        assertTrue(t.methods().contains("Result delete(Long)"));
        assertTrue(t.methods().contains("Result batchDelete(List<Long>)"));

        // 通配 import 被识别并标记
        JavaParserProbe.ImportSummary wildcard = fs.imports().stream()
                .filter(i -> i.name().equals("org.springframework.web.bind.annotation"))
                .findFirst().orElseThrow();
        assertTrue(wildcard.wildcard(), "通配 import 应被标记 wildcard=true");

        // 依赖边分类：项目内 vs 三方
        assertTrue(fs.imports().stream()
                        .filter(i -> i.name().equals("org.example.anisonmanage.dto.SongDTO"))
                        .allMatch(i -> "internal".equals(i.kind())),
                "项目内 import 应归类 internal");
        assertTrue(fs.imports().stream()
                        .filter(i -> i.name().equals("org.springframework.web.bind.annotation"))
                        .allMatch(i -> "external".equals(i.kind())),
                "Spring import 应归类 external");
    }

    @Test
    void extractRepositoryInterface() {
        String src = """
                package org.example.anisonmanage.repository;

                import org.example.anisonmanage.entity.Song;
                import org.springframework.data.jpa.repository.JpaRepository;

                import java.util.List;

                public interface SongRepository extends JpaRepository<Song, Long> {
                    int deleteBatchByIds(List<Long> ids);
                }
                """;
        JavaParserProbe.TypeSummary t = onlyType(src, "SongRepository.java");

        assertEquals("interface", t.kind());
        assertEquals("SongRepository", t.name());
        assertEquals(1, t.extendedTypes().size());
        assertEquals("JpaRepository<Song,Long>", t.extendedTypes().get(0));
        assertTrue(t.methods().contains("int deleteBatchByIds(List<Long>)"));
    }

    @Test
    void extractGenericClassWithStaticMethods() {
        String src = """
                package org.example.anisonmanage.pojo;

                import lombok.Data;

                @Data
                public class Result<T> {
                    private Integer code;
                    private T data;

                    public static <E> Result<E> success(E data) { return null; }
                    public static Result success() { return null; }
                    public Result error(String message) { return null; }
                }
                """;
        JavaParserProbe.TypeSummary t = onlyType(src, "Result.java");

        assertEquals("class", t.kind());
        assertEquals("Result", t.name());
        assertTrue(t.typeParameters().contains("T"), "泛型参数 T 应被抽出");
        assertTrue(t.methods().contains("static Result<E> success(E)"), "静态泛型方法签名");
        assertTrue(t.methods().contains("static Result success()"), "静态方法签名");
        assertTrue(t.methods().contains("Result error(String)"), "实例方法签名");
    }

    // ------------------------------------------------------------------
    // 2. Java 17 新语法（toy 项目缺失，用合成 fixture 补测）
    // ------------------------------------------------------------------

    @Test
    void parseJava17Record() {
        JavaParserProbe.TypeSummary t = onlyType("package demo; public record Point(int x, int y) {}", "Point.java");
        assertEquals("record", t.kind());
        assertEquals("Point", t.name());
        assertEquals(2, t.fields().size(), "record 组件应被当作字段抽出");
        assertTrue(t.fields().stream().anyMatch(f -> f.name().equals("x") && f.type().equals("int")));
        assertTrue(t.fields().stream().anyMatch(f -> f.name().equals("y") && f.type().equals("int")));
    }

    @Test
    void parseJava17Sealed() {
        String src = """
                package demo;
                public sealed interface Shape permits Circle, Square { }
                final class Circle implements Shape { }
                """;
        CompilationUnit cu = JavaParserProbe.parse(src);
        JavaParserProbe.FileSummary fs = JavaParserProbe.summarize(cu, "Shape.java", "demo");
        assertEquals(2, fs.types().size(), "sealed 接口 + 实现类都应被解析");

        JavaParserProbe.TypeSummary shape = fs.types().get(0);
        assertEquals("interface", shape.kind());
        assertEquals("Shape", shape.name());

        JavaParserProbe.TypeSummary circle = fs.types().get(1);
        assertEquals("class", circle.kind());
        assertTrue(circle.implementedTypes().contains("Shape"));
    }

    // ------------------------------------------------------------------
    // 3. 真实项目解析成功率（-Dprobe.root=真实代码目录）
    // ------------------------------------------------------------------

    @Test
    void parseRealProject() throws Exception {
        String rootProp = System.getProperty("probe.root");
        assumeTrue(rootProp != null && !rootProp.isBlank(), "跳过：未提供 -Dprobe.root");

        Path root = Path.of(rootProp);
        assertTrue(Files.isDirectory(root), "probe.root 不是目录: " + root);

        JavaParserProbe.ProbeResult r = new JavaParserProbe().probe(root);
        System.out.println("=== JavaParser 真实项目扫描摘要 ===");
        System.out.printf("项目包根: %s%n", r.projectRootPackage());
        System.out.printf("文件总数: %d | 成功: %d | 失败: %d%n",
                r.totalFiles(), r.successFiles(), r.failedFiles());
        int typeCount = r.files().stream().mapToInt(f -> f.types().size()).sum();
        int methodCount = r.files().stream().flatMap(f -> f.types().stream())
                .mapToInt(t -> t.methods().size()).sum();
        int fieldCount = r.files().stream().flatMap(f -> f.types().stream())
                .mapToInt(t -> t.fields().size()).sum();
        System.out.printf("类型: %d | 方法: %d | 字段: %d%n", typeCount, methodCount, fieldCount);
        r.failures().forEach(f -> System.out.println("  失败: " + f.relativePath() + " -> " + f.error()));

        // 断言：>0 个文件，成功率 ≥95%
        assertTrue(r.totalFiles() > 0, "应扫描到至少 1 个 .java 文件");
        double rate = (double) r.successFiles() / r.totalFiles();
        assertTrue(rate >= 0.95, "解析成功率应 ≥95%，实际 " + String.format("%.2f", rate));

        // 断言：关键文件被正确识别（类名/接口名）
        assertTrue(r.files().stream().anyMatch(f ->
                        f.relativePath().endsWith("Song.java")
                                && f.types().stream().anyMatch(t -> t.name().equals("Song"))),
                "应识别 entity/Song 类");
        assertTrue(r.files().stream().anyMatch(f ->
                        f.relativePath().endsWith("SongRepository.java")
                                && f.types().stream().anyMatch(t -> "interface".equals(t.kind()))),
                "应识别 repository/SongRepository 接口");

        // 输出 JSON 报告工件（供实验报告引用）
        Path out = Path.of("target", "java-parser-probe-report.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), r);
        System.out.println("JSON 报告已写出: " + out.toAbsolutePath());
    }

    // ------------------------------------------------------------------

    private static JavaParserProbe.TypeSummary onlyType(String src, String relPath) {
        CompilationUnit cu = JavaParserProbe.parse(src);
        JavaParserProbe.FileSummary fs = JavaParserProbe.summarize(cu, relPath, ROOT);
        assertEquals(1, fs.types().size(), "应只有一个顶层类型");
        return fs.types().get(0);
    }
}
