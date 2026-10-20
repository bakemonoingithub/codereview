package com.codereview.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 报告来源：正文、版本、当时反馈都在 snapshot 里；不联查最新数据代替冻结。 */
@Data
@TableName("report_source")
public class AgentReportSource {

    private String reportId;
    private String resultId;
    private String sourceSnapshot;
    private Integer ordinal;
}
