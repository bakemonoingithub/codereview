package com.codereview.agent.contract;

/**
 * 契约检查使用的错误分类。
 *
 * <p>同一套结构规则在不同业务里沿用各自的错误码：专项使用 {@code MISSING_FIELD}、
 * {@code INVALID_ENUM} 等协议码，Agent 提交统一使用 {@code RESULT_INVALID}。
 * 分类由调用方给出，规则本身只声明一次。
 */
public record ContractCodes(String unknownField, String missingField, String invalidEnum,
                            String typeMismatch, String tooLong, String tooMany, String tooFew,
                            String nullItem) {

    /** 专项协议码；与改造前 {@code SpecialistResultParser}/{@code SpecialistResultValidator} 一致。 */
    public static final ContractCodes SPECIALIST = new ContractCodes("UNKNOWN_FIELD",
            "MISSING_FIELD", "INVALID_ENUM", "TYPE_MISMATCH", "MISSING_FIELD", "MISSING_FIELD",
            "MISSING_TARGET", "INVALID_JSON");

    /** Agent 提交的结构错误统一归入 {@code RESULT_INVALID}。 */
    public static final ContractCodes AGENT_SUBMISSION = new ContractCodes("RESULT_INVALID",
            "RESULT_INVALID", "RESULT_INVALID", "RESULT_INVALID", "RESULT_INVALID",
            "RESULT_INVALID", "RESULT_INVALID", "RESULT_INVALID");
}
