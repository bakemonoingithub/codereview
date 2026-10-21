package com.codereview.agent.specialist;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 专项材料增强的资源上限。
 *
 * <p>默认值来自 P29 的候选参数（继承深度 6、辅助文件 12、辅助读取 256 KiB），
 * 只是本地验证起点；P31.9 要用目标仓库校准，不能在发起表单里暴露给用户。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.specialist")
public class SpecialistProperties {

    /** 继承查找最大深度；直接父类型算第 1 层。 */
    private int maxInheritanceDepth = 6;
    /** 每个目标最多读取/发送多少个不同辅助文件；失败候选也占读取次数。 */
    private int maxAuxiliaryFiles = 12;
    /** 全文取得后累计核算；超限候选不解析/发送，并停止后续读取。发送摘要另累计核算。 */
    private int maxAuxiliaryBytes = 256 * 1024;
    /** 是否补充字段、参数和返回类型的一层声明；默认开启。 */
    private boolean includeDirectDeclarations = true;
}
