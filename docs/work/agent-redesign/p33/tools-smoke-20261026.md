# 工具真实冒烟测试（2026-10-26）

负责人要求验证本会话调整后的工具。检查时服务仍为旧进程；确认 `RUNNING`／`QUEUED` 任务均为 0 后，使用开发脚本停止并重新启动前后端，加载 `40d41e9`。继续使用现有项目与模型，没有调整业务配置或预算。

## 样本与结果

- 任务：`01M5W6H0WS2BY13REPR4A741N2`，`TOOLS-SMOKE-20261026：全工具与搜索分页`。
- 项目：`codereview`（`01M5VNTMAMXP5EFT7RZCFV7XZ9`）；模型：`deepseek-flash`（`01M5VNQDTC9JEMTXQ9YYFGWZEM`，Agent 验证 PASSED）。
- 来源：现有远端 `dev` 的固定提交 `ef7f3d6ede649163098c65f4afe644f104f454de`；COMMITS 范围，`V1` 为该提交，`C1` 为与第一父提交的比较。
- 终态：`SUCCEEDED / ANSWERED`，4 轮模型调用、7 次只读工具尝试及 1 次提交，接口总耗时 30 秒。

| 工具 | 调用数 | 结构化结果 | 保存的 envelope 字节数 |
| --- | --- | --- | --- |
| `tree` | 1 | OK，省略 path，根目录首批 10 项；limit 触发 PARTIAL 与续页游标 | 951 |
| `find_files` | 1 | OK／COMPLETE，查找 `**/TaskService.java` | 377 |
| `read_code` | 1 | OK／COMPLETE，读取 TaskService 第 70–78 行并返回材料 | 1190 |
| `search_code` | 2 | 均 OK／PARTIAL，`tool_call`、limit 100，两页分别 30、31 条命中 | 32204／32398 |
| `read_diff` | 2 | 均 OK／COMPLETE，先读 C1 清单，再读 AgentLaunch.vue 的 hunk，返回 14 份前后侧材料 | 1236／12527 |
| `submit_result` | 1 | 首次通过，ANSWERED，5 条引用 | 127 |

上述体积通过业务库只读查询 `OCTET_LENGTH(envelope)` 核对，均小于冻结上限 32768 字节。搜索第二页原样使用第一页的 `nextCursor`，查询参数不变；游标偏移从 30 推进到 61，两页没有重复的「路径＋行号」。原始 grep 命中达到 200 条保留上限，本次仅检查前两页，不宣称读完全部命中。

所有材料引用均与本次工具返回的 materialId、路径、SHA 及合法行范围一致。运行期间读取到 `currentAction=MODEL_CALL`；本次没有出现工具 ERROR、整批拒绝或提交纠错。

## 语义限制

模型生成的总结存在两处数字错误：把 TaskService 类声明写成第 73 行，实际为第 74 行；把第二页搜索写成 30 条，实际为 31 条。已对照原始材料与结构化返回核实，历史结果正文保持原样，不以文本总结代替测试判据。

本次确认工具调用、分页边界与提交校验通过，不等于模型全部语义结论正确。未新增 UI 结构，未做负责人 PC 视觉验收；单条超长命中、4096 字节自定义上限及错误输入的覆盖仍采用前述工程回归证据。服务保留运行。
