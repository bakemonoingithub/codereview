-- =====================================================================
-- V7__prompt_content_capacity.sql  提示词正文的容量上限
--
-- 背景（backlog ⑥，随 ④ 一并核对得出）：
--   prompt_version.content 一直是 TEXT = 65535 **字节**，而用户可以把任意长的内容
--   粘进「提示词正文」并保存 —— 超限时数据库直接硬失败
--       errorCode=1406 / SQLState=22001 / "Data too long for column 'content'"
--   前端不会翻译这个错误，界面上只会出现数据库的原始英文报错：
--   用户既看不懂，也不知道问题是"太长了"。
--
--   现场判断（与 ④ 的取舍不同，这里没有"写坏记录"的风险，只有"保存不上"）：
--   容量本身该给足。16MB 相对"人手工粘贴的提示词"有数量级余量，
--   因此本次只扩容、不额外加写入侧的友好报错。
--
-- 说明：不动 V1__init.sql（Flyway 会校验已应用迁移的 checksum）。
-- =====================================================================

-- content 是 NOT NULL 列：MODIFY 必须把约束一起写上，否则等于顺手放宽了非空约束
ALTER TABLE prompt_version
    MODIFY content MEDIUMTEXT NOT NULL COMMENT '提示词正文';
