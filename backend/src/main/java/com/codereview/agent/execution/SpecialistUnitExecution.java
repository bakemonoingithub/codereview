package com.codereview.agent.execution;

import com.codereview.agent.specialist.capacity.SpecialistUnitPlan;
import com.codereview.agent.specialist.protocol.SpecialistResultValidator;

/** 一个成功执行的片段：单元计划、校验后的结果与最后一次成功调用。 */
public record SpecialistUnitExecution(String unitId, SpecialistUnitPlan plan,
                                      SpecialistResultValidator.ValidatedResult validated,
                                      String callId,
                                      com.codereview.agent.specialist.coupling.CouplingFacts couplingFacts) {
    public SpecialistUnitExecution(String unitId, SpecialistUnitPlan plan,
                                  SpecialistResultValidator.ValidatedResult validated, String callId) {
        this(unitId, plan, validated, callId, null);
    }
}
