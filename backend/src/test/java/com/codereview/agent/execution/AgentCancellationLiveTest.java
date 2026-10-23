package com.codereview.agent.execution;

import com.codereview.agent.api.ApiException;
import com.codereview.agent.db.*;
import com.codereview.agent.entity.*;
import com.codereview.agent.mapper.*;
import com.codereview.agent.support.*;
import com.codereview.agent.tools.AgentSubmission;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in: writes named test fixtures to the configured Agent database. */
@EnabledIfSystemProperty(named="agent.live.cancel", matches="true")
class AgentCancellationLiveTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private AgentDatabase db;
    private AgentReviewTask task;
    private AgentSubmission submission;
    private List<AgentSubmission.SentMaterial> materials;

    @BeforeEach void prepare() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        var p = yaml.getObject();
        var props = new AgentDataSourceProperties();
        props.setUrl(p.getProperty("agent.datasource.url"));
        props.setUsername(p.getProperty("agent.datasource.username"));
        props.setPassword(p.getProperty("agent.datasource.password"));
        db = new AgentDatabase(props, List.of(AgentReviewTaskMapper.class, AgentReviewResultMapper.class));
        task = db.mapper(AgentReviewTaskMapper.class).selectById("01M5K73RVKSC04RDQYSQBG032J");
        task.setId(AgentIds.next()); task.setName("AGT-019-R01 受控发布竞争");
        task.setState(TaskStates.RUNNING); task.setStopReason(null); task.setRevision(0);
        task.setIdempotencyKey(null); task.setRequestHash(null); task.setCancelRequestedAt(null);
        task.setCreatedAt(AgentTime.now()); task.setUpdatedAt(AgentTime.now());
        task.setStartedAt(AgentTime.now()); task.setFinishedAt(null);
        task.setDeadlineAt(AgentTime.now().plusMinutes(20));
        db.mapper(AgentReviewTaskMapper.class).insert(task);
        var source = get("/agent-tasks/01M5K73RVKSC04RDQYSQBG032J/result");
        var c = source.path("body").path("citations").get(0);
        var m = source.path("provenance").path("materials").iterator();
        JsonNode found=null; while(m.hasNext()){var n=m.next();if(n.path("id").asText().equals(c.path("materialId").asText()))found=n;}
        assertNotNull(found);
        materials=List.of(new AgentSubmission.SentMaterial(found.path("id").asText(),found.path("path").asText(),
                found.path("side").asText(),found.path("sha").asText(),found.path("startLine").asInt(),found.path("endLine").asInt(),found.path("contentHash").asText()));
        submission=new AgentSubmission("AGT-019 事务边界验证","受控发布，不代表新模型分析。",AgentSubmission.ANSWERED,
                "复用已验证材料测试发布事务。[E1]",List.of(new AgentSubmission.Citation("E1",c.path("materialId").asText(),c.path("startLine").asInt(),c.path("endLine").asInt())),List.of());
    }
    @AfterEach void close(){if(db!=null)db.destroy();}
    private JsonNode get(String path)throws Exception{return json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081/api/v2"+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).body());}
    private JsonNode cancel()throws Exception{
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081/api/v2/review-tasks/"+task.getId()+"/cancel"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"expectedRevision\":0}")).build();
        var r=http.send(req,HttpResponse.BodyHandlers.ofString());assertEquals(200,r.statusCode());return json.readTree(r.body());
    }
    private void record(String order)throws Exception{
        var node=json.createObjectNode();node.put("caseId","AGT-019");node.put("taskId",task.getId());node.put("order",order);
        node.set("task",get("/review-tasks/"+task.getId()));
        node.put("resultCount",db.mapper(AgentReviewResultMapper.class).selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentReviewResult>().eq(AgentReviewResult::getTaskId,task.getId())));
        var dir=Path.of("target/agt-batch");Files.createDirectories(dir);Files.writeString(dir.resolve("AGT-019-"+order+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(node));
    }
    @Test @DisplayName("AGT-019 cancellation commits before publisher locks task")
    void cancelFirst()throws Exception{
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        var real=db.mapper(AgentReviewTaskMapper.class);
        var blocked=mock(AgentReviewTaskMapper.class,org.mockito.AdditionalAnswers.delegatesTo(real));
        doAnswer(i->{reached.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));return real.selectOne(i.getArgument(0));}).when(blocked).selectOne(any());
        var wrapper=spy(db);doReturn(blocked).when(wrapper).mapper(AgentReviewTaskMapper.class);
        var executor=Executors.newSingleThreadExecutor();
        try{
            var f=executor.submit(()->new AgentResultPublisher(wrapper,json).publish(task.getId(),submission,materials,List.of(),1));
            assertTrue(reached.await(20,TimeUnit.SECONDS));
            assertEquals(TaskStates.CANCELLED,cancel().path("state").asText());release.countDown();
            var error=assertThrows(ExecutionException.class,()->f.get(20,TimeUnit.SECONDS));assertInstanceOf(ApiException.class,error.getCause());
            assertEquals(TaskStates.CANCELLED,get("/review-tasks/"+task.getId()).path("state").asText());
            assertEquals(0L,db.mapper(AgentReviewResultMapper.class).selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentReviewResult>().eq(AgentReviewResult::getTaskId,task.getId())));
            record("cancel-first");
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test @DisplayName("AGT-019 publication commits before cancellation")
    void publishFirst()throws Exception{
        task.setName("AGT-019-R02 发布先提交");db.mapper(AgentReviewTaskMapper.class).updateById(task);
        new AgentResultPublisher(db,json).publish(task.getId(),submission,materials,List.of(),1);
        assertEquals(TaskStates.SUCCEEDED,cancel().path("state").asText());
        assertEquals(1L,db.mapper(AgentReviewResultMapper.class).selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentReviewResult>().eq(AgentReviewResult::getTaskId,task.getId())));
        record("publish-first");
    }
}
