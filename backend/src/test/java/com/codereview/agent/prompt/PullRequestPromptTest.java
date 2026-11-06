package com.codereview.agent.prompt;

import com.codereview.agent.specialist.material.SpecialistMaterials;
import com.codereview.agent.specialist.protocol.SpecialistPromptBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR 提示词只改变 PR 用途；既有提交差异文案仍由冻结基线保护。 */
class PullRequestPromptTest {

    @Test
    void pullRequestPromptNamesTheMergeBaseAndDoesNotCallItTheFirstParent() {
        SpecialistMaterials.Material after = new SpecialistMaterials.Material("m1", "p",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "src/A.java",
                SpecialistMaterials.Side.AFTER, 1, 2, "class A {}\n", "hash", "COMPLETE", null,
                SpecialistMaterials.Role.TARGET, SpecialistMaterials.Status.RESOLVED, null, null);
        SpecialistMaterials.PreparedTarget target = new SpecialistMaterials.PreparedTarget("p", "ISSUE", "PR",
                "dev", "src/A.java", null, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", null, false, false, List.of(after),
                List.of(), List.of(new SpecialistMaterials.Gap("UNCOVERED_SCOPE", "src/A.java",
                "另有 1 个文本文件未选择。")), null, null,
                new SpecialistMaterials.PullRequestContext(12, "feature", "dev",
                        "cccccccccccccccccccccccccccccccccccccccc", "另有 1 个文本文件未选择。"));

        AssembledPrompt prompt = new PromptAssemblyService().assemble(
                new SpecialistPromptBuilder().build(target, null, null, null));

        assertTrue(prompt.systemPrompt().contains("共同祖先到源提交的净变化"));
        assertFalse(prompt.systemPrompt().contains("此次提交差异"));
        assertTrue(prompt.userPrompt().contains("编号：#12"));
        assertTrue(prompt.userPrompt().contains("共同祖先：bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertTrue(prompt.userPrompt().contains("不是第一父提交"));
        assertTrue(prompt.userPrompt().contains("另有 1 个文本文件未选择。"));
        assertFalse(prompt.systemPrompt().contains("instruction.ISSUE_PR"));
    }
}
