# PAS-01 实施检查命令

以下均在 `H:\myProjects\aiCodeReview\code\backend` 执行。每次 shell 子进程先设置 `JAVA_HOME=C:/Users/ysx/.jdks/azul-17.0.20.1`，移除 DEEPSEEK_API_KEY、GITEA_TEST_BASE_URL、JAVA_TOOL_OPTIONS、JDK_JAVA_OPTIONS、MAVEN_OPTS；不启用 probe.root。构建目录分别隔离，未运行后端全量或 package。

## 改造前

```powershell
./mvnw.cmd -q '-Ddeepseek.api-key=' '-Dagent.live.cancel=false' '-Dtest=PromptRefactorBaselineTest,ToolDefinitionBaselineTest,PromptCorrectionTest' '-Dbackend.buildDirectory=target-pas01-before' test
```

随后在原生产代码未改动时捕获新的边界快照。此命令已完成，只记录来源，后续不得重录：

```powershell
./mvnw.cmd -q '-Ddeepseek.api-key=' '-Dagent.live.cancel=false' '-Dtest=PromptAssemblyBoundaryBaselineTest' '-Dpas.captureBoundaryBaseline=true' '-Dbackend.buildDirectory=target-pas01-before' test
```

## 候选基本检查

```powershell
./mvnw.cmd -q '-Ddeepseek.api-key=' '-Dagent.live.cancel=false' '-Dtest=PromptRefactorBaselineTest,ToolDefinitionBaselineTest,PromptAssemblyBoundaryBaselineTest,PromptAssemblyServiceTest,PromptAssemblyAgentTest,PromptCorrectionTest,PromptTemplateRegistryTest,SpecialistPromptBuilderTest,AgentToolSchemaTest,ModelCapacityServiceTest,SpecialistSplitPlannerTest' '-Dbackend.buildDirectory=target-pas01-after' test
```

```powershell
./mvnw.cmd -q '-Ddeepseek.api-key=' '-Dagent.live.cancel=false' '-Dtest=PromptAssemblyCompositionTest,SpecialistTaskWorkerTest,AgentTaskWorkerTest,ReportExecutionWorkerTest,ModelInvocationServiceTest,AgentToolLoopValidatorTest,AgentContractMaintenanceTest,ToolContractMaintenanceTest,SpecialistOutputContractsTest' '-Dbackend.buildDirectory=target-pas01-callers' test
```
