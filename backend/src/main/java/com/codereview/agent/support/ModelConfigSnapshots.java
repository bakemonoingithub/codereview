package com.codereview.agent.support;

import com.codereview.agent.entity.AgentModelConfig;
import com.codereview.agent.invocation.ModelSnapshot;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 三类入口冻结模型配置时的共同字段与序列化规则。
 *
 * <p>外层快照结构、字段顺序及场景差异仍由各入口保留：专项冻结接口地址和分词器，
 * 报告使用相同字段但在未声明协议时冻结默认协议，Agent 只冻结执行所需字段。
 * 凭据不属于任何快照。
 *
 * <p>维护关联 BRC-M08：字段顺序、字段存在性、null 表达和默认协议按入口保持；
 * 修改时核对 ModelConfigSnapshotsTest 及 maintenance-map.md，不改写历史快照。
 */
public final class ModelConfigSnapshots {

    private ModelConfigSnapshots() {
    }

    /** 专项任务：保留连接字段与分词器，协议按配置原值冻结。 */
    public static ObjectNode specialist(AgentModelConfig model) {
        ObjectNode node = baseWithConnection(model);
        node.put("protocol", model.getProtocol());
        node.put("revision", model.getRevision());
        return node;
    }

    /** 报告：字段与专项一致，未声明协议时冻结为默认协议。 */
    public static ObjectNode report(AgentModelConfig model) {
        ObjectNode node = baseWithConnection(model);
        node.put("protocol", model.getProtocol() == null ? ModelSnapshot.DEFAULT_PROTOCOL : model.getProtocol());
        node.put("revision", model.getRevision());
        return node;
    }

    /** Agent：只保留执行所需字段，不写接口地址和分词器。 */
    public static ObjectNode agent(AgentModelConfig model) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        putIdentity(node, model);
        node.put("protocol", model.getProtocol());
        node.put("revision", model.getRevision());
        putCapacity(node, model);
        return node;
    }

    private static ObjectNode baseWithConnection(AgentModelConfig model) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", model.getId());
        node.put("name", model.getName());
        node.put("baseUrl", model.getBaseUrl());
        node.put("modelName", model.getModelName());
        putCapacity(node, model);
        node.put("tokenizer", model.getTokenizer());
        return node;
    }

    private static void putIdentity(ObjectNode node, AgentModelConfig model) {
        node.put("id", model.getId());
        node.put("name", model.getName());
        node.put("modelName", model.getModelName());
    }

    private static void putCapacity(ObjectNode node, AgentModelConfig model) {
        putNullable(node, "contextWindow", model.getContextWindow());
        putNullable(node, "maxOutput", model.getMaxOutput());
    }

    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else if (value instanceof Integer number) {
            node.put(field, number);
        } else {
            node.put(field, String.valueOf(value));
        }
    }
}
