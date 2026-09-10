package com.codereview.material;

import com.codereview.git.GitHostClient;
import com.codereview.git.GitRepoRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 共享物料制备：按范围拉取源码 → 结构抽取，按「commitSha + 范围」内存缓存复用。 */
@Component
public class MaterialService {

    private final GitHostClient gitHostClient;
    private final StructureExtractor extractor = new StructureExtractor();
    private final MaterialCache cache;

    public MaterialService(GitHostClient gitHostClient, MaterialCache cache) {
        this.gitHostClient = gitHostClient;
        this.cache = cache;
    }

    public Material prepare(String token, Integer credentialType, GitRepoRef ref, String branch, String commitSha, List<String> scope) {
        // 优先按 commit sha 取内容：分支会移动，按分支取会与记录下来的提交不一致
        String contentRef = (commitSha == null || commitSha.isBlank()) ? branch : commitSha;
        return cache.getOrCompute(MaterialCache.key(commitSha, scope), () -> {
            List<SourceFile> files = new ArrayList<>();
            for (String path : scope) {
                try {
                    files.add(new SourceFile(path, gitHostClient.rawFile(token, credentialType, ref.owner(), ref.repo(), contentRef, path)));
                } catch (Exception ignored) {
                    // 单文件拉取失败跳过
                }
            }
            return extractor.extract(files);
        });
    }
}
