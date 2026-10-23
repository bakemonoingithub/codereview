package com.codereview.agent.api;

import com.codereview.agent.dto.TaskDtos;
import com.codereview.agent.service.TaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 实际调用正文读取：只返回已保存的请求或响应，不按当前模板重建历史。 */
@RestController
@RequestMapping("/api/v2/model-calls")
@ConditionalOnProperty(prefix = "agent.datasource", name = "url")
public class ModelCallV2Controller {

    private final TaskService service;

    public ModelCallV2Controller(TaskService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public TaskDtos.CallDetail detail(@PathVariable String id,
                                      @RequestParam(defaultValue = "response") String part) {
        return service.callDetail(id, part);
    }

    @GetMapping("/{id}/full")
    public TaskDtos.CallDetailFull full(@PathVariable String id) {
        return service.callDetailFull(id);
    }
}
