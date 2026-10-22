package com.codereview.agent.tools;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.api.ErrorCode;
import com.codereview.agent.repository.RepositoryReadService;
import com.codereview.agent.repository.RepositorySnapshots;
import com.codereview.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读工具入口：严格解析参数、路由到具体工具、把可恢复失败包成 envelope。
 *
 * <p>工具参数错误、固定版本不存在、文件不可读与超时都作为 {@code status} 返回给模型，
 * 由模型决定替代调查；只有数据库等任务级故障才向上抛。所有返回在交付前检查 32 KiB 上限。
 */
@Service
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ReadOnlyToolService {

    private final ObjectMapper mapper;
    private final ToolArgumentParser parser;
    private final RepositoryReadService repository;
    private final ReadCodeTool readCode;
    private final SearchCodeTool searchCode;
    private final ReadDiffTool readDiff;

    public ReadOnlyToolService(ObjectMapper mapper, RepositoryReadService repository,
                               ReadCodeTool readCode, SearchCodeTool searchCode,
                               ReadDiffTool readDiff) {
        this.mapper = mapper;
        this.parser = new ToolArgumentParser(mapper);
        this.repository = repository;
        this.readCode = readCode;
        this.searchCode = searchCode;
        this.readDiff = readDiff;
    }

    public ToolEnvelope execute(String toolCallId, String toolName, String argumentsJson,
                                AgentToolScope scope) {
        ToolInvocation invocation;
        try {
            invocation = parser.parse(toolName, argumentsJson, scope);
        } catch (ToolFailure failure) {
            return failure(toolCallId, failure.status(), scope, failure.getMessage());
        }

        ToolEnvelope envelope;
        try {
            envelope = switch (toolName) {
                case AgentToolNames.TREE -> tree(toolCallId, invocation, scope);
                case AgentToolNames.FIND_FILES -> findFiles(toolCallId, invocation, scope);
                case AgentToolNames.SEARCH_CODE -> searchCode.execute(toolCallId, invocation, scope);
                case AgentToolNames.READ_CODE -> readCode.execute(toolCallId, invocation, scope);
                case AgentToolNames.READ_DIFF -> readDiff.execute(toolCallId, invocation, scope);
                default -> failure(toolCallId, ToolEnvelope.INVALID_ARGUMENT, scope,
                        "不支持的工具：" + toolName);
            };
        } catch (ToolFailure failure) {
            return failure(toolCallId, failure.status(), scope, failure.getMessage());
        } catch (ApiException e) {
            return mapApiFailure(toolCallId, e, scope);
        } catch (BusinessException e) {
            return failure(toolCallId, ToolEnvelope.UNREADABLE, scope,
                    "仓库读取失败：" + e.getMessage());
        } catch (RuntimeException e) {
            return failure(toolCallId, ToolEnvelope.ERROR, scope,
                    "工具执行异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        int size = ToolEnvelopes.size(mapper, envelope);
        if (size > ToolLimits.ENVELOPE_MAX_BYTES) {
            return failure(toolCallId, ToolEnvelope.ERROR, scope,
                    "工具返回超过 " + ToolLimits.ENVELOPE_MAX_BYTES + " 字节上限（" + size + "），已拒绝发送");
        }
        return envelope;
    }

    private ToolEnvelope tree(String toolCallId, ToolInvocation invocation, AgentToolScope scope) {
        RepositorySnapshots.PathList paths =
                repository.listPaths(scope.projectId(), invocation.sha());
        String base = invocation.path() == null ? "" : invocation.path();
        RepositorySnapshots.PathEntry baseEntry = base.isEmpty() ? null : findEntry(paths, base);
        if (!base.isEmpty() && baseEntry != null && "blob".equals(baseEntry.type())) {
            return failure(toolCallId, ToolEnvelope.INVALID_ARGUMENT, scope,
                    "path 指向文件，请使用 read_code：" + base);
        }
        List<RepositorySnapshots.PathEntry> candidates = new ArrayList<>();
        for (RepositorySnapshots.PathEntry entry : paths.entries()) {
            String relative = relative(base, entry.path());
            if (relative == null || relative.isEmpty()) {
                continue;
            }
            int depth = (int) relative.chars().filter(ch -> ch == '/').count() + 1;
            if (depth <= invocation.depth()) {
                candidates.add(entry);
            }
        }
        if (!base.isEmpty() && baseEntry == null && candidates.isEmpty()) {
            return failure(toolCallId, ToolEnvelope.NOT_FOUND, scope, "目录不存在：" + base);
        }
        candidates.sort(Comparator.comparing(RepositorySnapshots.PathEntry::path));

        ToolCursor cursor = ToolCursor.decode(invocation.cursor(), invocation);
        int offset = Math.min(cursor.offset(), candidates.size());
        List<Map<String, Object>> entries = new ArrayList<>();
        int budget = ToolLimits.ENVELOPE_MAX_BYTES - 512;
        int index = offset;
        while (index < candidates.size() && entries.size() < invocation.limit()) {
            RepositorySnapshots.PathEntry entry = candidates.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.path());
            item.put("type", entry.type());
            item.put("depth", depthOf(base, entry.path()));
            if ("tree".equals(entry.type())) {
                item.put("childCount", countChildren(paths.entries(), entry.path()));
            } else {
                item.put("reviewable", entry.reviewable());
            }
            int cost = entry.path().length() * 3 + 120;
            if (!entries.isEmpty() && cost > budget) {
                break;
            }
            budget -= cost;
            entries.add(item);
            index++;
        }

        boolean more = index < candidates.size();
        boolean truncated = paths.truncated();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", base.isEmpty() ? "/" : base);
        data.put("depth", invocation.depth());
        data.put("entries", entries);
        return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(), null, List.of(),
                data, truncated ? ToolEnvelope.UNKNOWN
                        : more ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE,
                more ? ToolCursor.encode(invocation, index) : null,
                truncated ? "宿主返回的路径清单不完整，不能据此判断目录已全部列出" : null);
    }

    private ToolEnvelope findFiles(String toolCallId, ToolInvocation invocation, AgentToolScope scope) {
        RepositorySnapshots.PathList paths =
                repository.listPaths(scope.projectId(), invocation.sha());
        String base = invocation.path() == null ? "" : invocation.path();
        List<RepositorySnapshots.PathEntry> matches = new ArrayList<>();
        for (RepositorySnapshots.PathEntry entry : paths.entries()) {
            if (!"blob".equals(entry.type())) {
                continue;
            }
            if (!base.isEmpty() && !entry.path().startsWith(base + "/")) {
                continue;
            }
            if (ToolGlobs.matches(invocation.glob(), entry.path())) {
                matches.add(entry);
            }
        }
        ToolCursor cursor = ToolCursor.decode(invocation.cursor(), invocation);
        int offset = Math.min(cursor.offset(), matches.size());
        List<String> page = new ArrayList<>();
        int budget = ToolLimits.ENVELOPE_MAX_BYTES - 512;
        int index = offset;
        while (index < matches.size() && page.size() < invocation.limit()) {
            String path = matches.get(index).path();
            int cost = path.length() + 40;
            if (!page.isEmpty() && cost > budget) {
                break;
            }
            budget -= cost;
            page.add(path);
            index++;
        }
        boolean more = index < matches.size();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("glob", invocation.glob());
        if (!base.isEmpty()) {
            data.put("path", base);
        }
        data.put("totalMatches", matches.size());
        data.put("matches", page);
        return new ToolEnvelope(toolCallId, ToolEnvelope.OK, scope.versionRefs(), null, List.of(),
                data, paths.truncated() ? ToolEnvelope.UNKNOWN
                        : more ? ToolEnvelope.PARTIAL : ToolEnvelope.COMPLETE,
                more ? ToolCursor.encode(invocation, index) : null,
                paths.truncated() ? "宿主返回的路径清单不完整，匹配结果可能缺失" : null);
    }

    /** 失败的统一形态：不带材料、complete 为 UNKNOWN，诊断只说明原因。 */
    private static ToolEnvelope failure(String toolCallId, String status, AgentToolScope scope,
                                        String diagnostic) {
        return new ToolEnvelope(toolCallId, status, scope.versionRefs(), null, List.of(), null,
                ToolEnvelope.UNKNOWN, null, diagnostic);
    }

    private static ToolEnvelope mapApiFailure(String toolCallId, ApiException e, AgentToolScope scope) {
        String status = switch (e.errorCode()) {
            case NOT_FOUND -> ToolEnvelope.NOT_FOUND;
            case FILE_NOT_READABLE, SNAPSHOT_INCOMPLETE, REPO_UNAUTHORIZED, REPO_UNAVAILABLE,
                 REPO_UNREACHABLE -> ToolEnvelope.UNREADABLE;
            case UPSTREAM_TIMEOUT -> ToolEnvelope.TIMEOUT;
            case VALIDATION_FAILED -> ToolEnvelope.INVALID_ARGUMENT;
            default -> ToolEnvelope.ERROR;
        };
        return failure(toolCallId, status, scope, e.getMessage());
    }

    private static RepositorySnapshots.PathEntry findEntry(RepositorySnapshots.PathList paths,
                                                           String path) {
        for (RepositorySnapshots.PathEntry entry : paths.entries()) {
            if (entry.path().equals(path)) {
                return entry;
            }
        }
        return null;
    }

    private static String relative(String base, String path) {
        if (base.isEmpty()) {
            return path;
        }
        String prefix = base + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : null;
    }

    private static int depthOf(String base, String path) {
        String relative = relative(base, path);
        if (relative == null || relative.isEmpty()) {
            return 0;
        }
        return (int) relative.chars().filter(ch -> ch == '/').count() + 1;
    }

    /** 只数下一层直接子项，与“打开该目录、深度 1”列出的条目数一致。 */
    private static int countChildren(List<RepositorySnapshots.PathEntry> entries, String path) {
        String prefix = path + "/";
        int count = 0;
        for (RepositorySnapshots.PathEntry entry : entries) {
            if (!entry.path().startsWith(prefix)) {
                continue;
            }
            String rest = entry.path().substring(prefix.length());
            if (!rest.isEmpty() && rest.indexOf('/') < 0) {
                count++;
            }
        }
        return count;
    }
}
