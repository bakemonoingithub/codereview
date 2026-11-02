package com.codereview.agent.prompt;

import com.codereview.agent.prompt.template.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** The facade must work outside Spring and must not share mutable request state. */
class PromptAssemblyCompositionTest {
    @Test
    void springAndStandaloneConstructionUseTheSameAssembly() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(PromptAssemblyService.class);
            context.refresh();
            assertEquals(new PromptAssemblyService().assemble(validation()),
                    context.getBean(PromptAssemblyService.class).assemble(validation()));
        }
    }

    @Test
    void repeatedAndConcurrentRequestsDoNotLeakIntoEachOther() throws Exception {
        var assembly = new PromptAssemblyService();
        var input = validation();
        var expected = assembly.assemble(input);
        var agent = new AgentPromptInput("读取指定文件", "FILES", "dev", "sha", null,
                List.of("A.java"), List.of(), List.of(), null, null);
        List<PromptMessage> history = new ArrayList<>(List.of(new PromptMessage("assistant", "原历史")));
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<AssembledPrompt>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                tasks.add(() -> {
                    assembly.assembleAgent(agent, history, List.of());
                    return assembly.assemble(input);
                });
            }
            for (var result : executor.invokeAll(tasks)) {
                assertEquals(expected, result.get());
            }
            assertEquals(List.of(new PromptMessage("assistant", "原历史")), history);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingManifestFailsWhenFacadeIsConstructed() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader missingManifest = new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return PromptTemplateRegistry.MANIFEST_PATH.equals(name) ? null : super.getResourceAsStream(name);
            }
        };
        try {
            Thread.currentThread().setContextClassLoader(missingManifest);
            IllegalStateException failure = assertThrows(IllegalStateException.class, PromptAssemblyService::new);
            assertTrue(failure.getMessage().contains("缺少提示词模板清单"));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static PromptInput validation() {
        return new PromptInput(PromptPurpose.VALIDATION, "1", null, null, null, "VALIDATION",
                null, null, null, List.of(), List.of(), List.of(), List.of(), null);
    }
}
