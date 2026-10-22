package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 调用与材料的关联。
 *
 * <p>复合主键不交给 MyBatis-Plus 的 {@code BaseMapper} 按 id 操作；访问按
 * {@code call_id} 或 {@code material_id} 条件查询与批量插入。
 */
@Data
@TableName("call_material")
public class AgentCallMaterial {

    private String callId;
    private String materialId;
    private String role;
    private String disposition;
}
