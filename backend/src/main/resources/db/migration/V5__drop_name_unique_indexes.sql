-- =====================================================================
-- V5__drop_name_unique_indexes.sql  移除四个实体的名称/地址唯一索引
--
-- 背景：唯一索引 + 逻辑删除（@TableLogic）会让**已删除**的记录继续占用唯一键，
--       于是"删掉后用同一个名称/地址重建"必然失败。
--       同类问题在本项目已发作过一次：见 V2__issue_mark.sql 的说明
--       （撤销标记写回 0 而不删行，就是为了绕开 uk_record_unit_issue）。
--
-- 为什么不改唯一索引的定义：MySQL **不支持部分唯一索引**（即"仅对 is_deleted = 0 唯一"），
--       所以无法在 DDL 层表达"未删除范围内唯一"，只能下移到应用层。
--
-- 应用层承接点（本迁移必须与它们同一提交落地，否则中间会出现"既无索引也无校验"的空窗）：
--   ProjectService.create        → 仓库地址在未删除项目中唯一
--   ModelConfigService create/update
--   PromptService      create/update
--   ReviewStrategyService create/update
-- 判重依赖 MyBatis-Plus 逻辑删除自动补 is_deleted = 0，故已删除记录不参与判重。
--
-- 说明：不动 V1__init.sql（Flyway 会校验已应用迁移的 checksum），沿用 V4 的既有做法。
-- =====================================================================

ALTER TABLE project         DROP INDEX uk_gitea_url;
ALTER TABLE prompt          DROP INDEX uk_name;
ALTER TABLE model_config    DROP INDEX uk_name;
ALTER TABLE review_strategy DROP INDEX uk_name;
