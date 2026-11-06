# PRS-01 接口、材料与基线

日期：2026-11-06。业务事实以[契约第 13 节](../agent-redesign/business-contract.md#13-pr-手工审查范围2026-11-06)为准。本文只记录 PRS-01 选定的接口和材料路线，以及 PRS-02、PRS-03 的实现入口。PRS-04 至 PRS-06 不在本文范围内。

## 1. 材料路线

比较了三条候选：

| 候选 | 不采用的原因 |
| --- | --- |
| GitHub `GET /repos/{owner}/{repo}/pulls/{n}/files` | 最多 3000 个文件；达到上限不能当成完整清单。patch 缺失也不能区分二进制。比较对象会随 PR 移动，不能代替冻结提交。 |
| Gitea `GET /repos/{owner}/{repo}/pulls/{index}.diff` | Gitea 1.16.1 没有逐文件 JSON。diff 文本不是默认材料来源；镜像已经能按固定提交比较。 |
| 两端各自的提交接口加第一父提交 | 第一父提交不是 PR 的共同祖先。目标分支独有提交会被算进差异，或把源分支历史拆成逐提交审查。 |

选定路线：GitHub 与 Gitea 都只新增 PR 列表和详情读取。净变化由共享 `GitMirror` 计算：取源提交与目标提交的共同祖先，再比较共同祖先树和源提交树。重命名检测打开。没有共同祖先时失败，不退回某一侧提交。这与现有 `GiteaGitMirror.changedPaths` 在无祖先时退回 base 树的行为不同；旧路径列表保持原样，PR 比较不调用它。

文件类别看 Git 模式和对象内容，不看扩展名，也不看 patch 是否为空：

| 类别 | 条件 | 能否成为问题专项目标 |
| --- | --- | --- |
| TEXT | 常规文件，无 NUL，体积不超过现有读取上限，可按 UTF-8 解码，且不是 LFS 指针 | 可以 |
| BINARY | 内容含 NUL | 不可以 |
| UNREADABLE / SUBMODULE | gitlink | 不可以 |
| UNREADABLE / SYMLINK | 符号链接，不跟随目标 | 不可以 |
| UNREADABLE / LFS_POINTER | 文本以 Git LFS 指针开头；不读取 LFS 对象 | 不可以 |
| UNREADABLE / TOO_LARGE | 超过 `agent.repository.max-file-bytes`，默认 4 MiB | 不可以 |
| UNREADABLE / ENCODING | 不是合法 UTF-8 | 不可以 |
| UNREADABLE / MISSING | 对应对象不存在 | 不可以 |

超过 3000 个变更文件时，比较结果为 `TRUNCATED`，不返回可选用的部分路径。创建任务得到 `SNAPSHOT_INCOMPLETE`。

## 2. 新增只读接口

宿主调用使用项目现有凭据。GitHub 为 `GET /repos/{owner}/{repo}/pulls` 与 `GET /repos/{owner}/{repo}/pulls/{pull_number}`。Gitea 1.16.1 为 `GET /repos/{owner}/{repo}/pulls` 与 `GET /repos/{owner}/{repo}/pulls/{index}`。列表状态为 `open`、`closed` 或 `all`，默认 `open`。GitHub 用 `base` 过滤目标分支；Gitea 1.16.1 没有该查询参数，只过滤当前页，`hasMore` 仍跟随宿主页。

本系统接口：

| 方法与路径 | 作用 |
| --- | --- |
| `GET /api/v2/projects/{projectId}/pulls` | 查询 `state`、`targetBranch`、`page`、`perPage`。返回 `items` 与 `hasMore`。 |
| `GET /api/v2/projects/{projectId}/pulls/{number}` | 返回 PR 身份、两侧 SHA、共同祖先和 `blocker`。 |
| `GET /api/v2/projects/{projectId}/pulls/{number}/changes` | 返回当前净变化、完整性、文本／二进制／不可读计数和文件类别。 |
| `POST /api/v2/review-tasks` | `source.kind=PR` 时提交 `pullNumber`、`sourceHead`、`targetHead`、`mergeBase` 和文本目标。 |

`blocker` 与创建失败：

| 条件 | blocker / 错误 |
| --- | --- |
| 源或目标仓库不是当前项目 | `CROSS_REPOSITORY`，创建为 `VALIDATION_FAILED` |
| 非 open，或已经合并 | `PR_NOT_OPEN`，创建为 `VALIDATION_FAILED` |
| 没有共同祖先 | `NO_MERGE_BASE`，创建为 `VALIDATION_FAILED` |
| 变更文件超过 3000 | `TRUNCATED`，创建为 `SNAPSHOT_INCOMPLETE` |
| 三个确认版本与重新读取的结果不一致 | `SOURCE_MOVED` |
| 目标不是文本净变化 | `VALIDATION_FAILED`，不写入任务目标 |
| 没有可审查文本变更 | `VALIDATION_FAILED`，原因 `NO_TEXT_CHANGES` |

设计模式和耦合度不能使用 PR 范围。单提交 `DIFF` 与文件范围的请求字段保持原样。

创建成功后，`inputSnapshot.source` 保存 PR 编号、标题、地址、宿主、源分支、目标分支、三个 SHA 和覆盖说明。`coverage` 保存完整性、各类计数、已选数量和最多 20 条未选文本路径。`task_target.headSha` 是源提交，`baseSha` 是共同祖先。不新增表或列。复制参数返回 PR 编号，并要求重新观察；不把旧的三个 SHA 当作可直接提交的值。

## 3. 问题专项

每个选中的文本文件仍是一个目标。新增只有 AFTER，删除只有 BEFORE，重命名和复制保留新旧路径。声明增强继续按材料上的 SHA 读取，因此前侧用共同祖先，后侧用源提交。

提示词新增用途 `ISSUE_PR`。输出结构与问题差异相同，`changeRelation` 必填。系统指令改为 PR 净变化，不再写「此次提交差异」。用户段落写明共同祖先、源提交和目标提交，并写明 BEFORE／AFTER 不是第一父提交。PR 标题和描述不进入提示词。未覆盖说明进入该目标的缺失信息。既有 `ISSUE_DIFF` 模板和 PAR／PAS 冻结请求不改；`ISSUE_PR` 不写入那两份冻结文件。

## 4. 单提交基线

以下行为在本轮保持不变，并由原有测试继续锁定：

- `CommitDetail.baseSha()` 仍是第一父提交；合并提交取 `parents[0]`，根提交为 null。
- `RepositoryReadService.diff(projectId, headSha, path)` 仍按该第一父提交取 BEFORE。
- `GiteaGitMirror.changedPaths` 仍是路径列表；无共同祖先时仍退回 base 树。
- `source.kind=DIFF` 创建任务不读取 PR，也不调用 `diffRange`。

PR 材料走 `RepositoryReadService.diffRange(projectId, mergeBase, sourceHead, path)`。这里的第一个 SHA 已经是共同祖先，方法不再另取父提交。

## 5. 未实现

Agent 的 `read_diff`、发起页、详情页、报告来源和 GitHub／Gitea 真实仓库样本分别留在 PRS-04 至 PRS-06。本地镜像不可用时，PR 净变化直接失败，不改调 PR diff。跨仓库 PR 不克隆 fork。
