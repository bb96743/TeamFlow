-- M10 交付后修复（V15）：todo_item 幂等唯一键由「终身唯一」改为「仅未处理唯一」
--
-- 背景（交付复核发现的功能缺陷）：
--   V13 的唯一键 uk_todo_item_biz (biz_type, biz_id, user_id, action_type) **不含状态**，
--   导致同一事项对同一用户终身只能存在一行。典型故障：
--     任务派给张三 → 张三在收件箱处理（status=handled）→ 任务被退回并重新派给张三 →
--     再次投递时唯一键冲突，收件箱**不再出现待办**（收件箱可靠性硬伤）。
--
-- 修复：
--   改用「生成列 + 唯一索引」实现"仅未处理唯一"（MySQL 8 不支持部分索引）：
--     active_uk 仅在 status='open' 且未删除时有值，handled/ignored 行一律为 NULL；
--     MySQL 唯一索引允许多个 NULL → 历史记录可共存、互不冲突；open 行仍唯一（R2 不变）。
--
-- R4 语义随之修正（与原意一致：已关闭不得被"同一事件"重开，需新事件才允许）：
--   已处理行本身**不可被修改/重开**（标题、状态、处理结果都不变）；
--   重复投递视为新的业务事件，**新建**一条 open 记录，历史保留。
--
-- 说明：本脚本为 V15 追加脚本（L0 §5.3.1 G-4 规则：只追加 V15+，禁改 V1–V14）。
-- 幂等：由 Flyway 版本保证只执行一次；脚本内先删旧唯一键再加生成列与新唯一键。

ALTER TABLE todo_item DROP INDEX uk_todo_item_biz;

ALTER TABLE todo_item
    ADD COLUMN active_uk VARCHAR(200)
        GENERATED ALWAYS AS (
            IF(status = 'open' AND deleted = 0,
               CONCAT(biz_type, '|', biz_id, '|', user_id, '|', action_type),
               NULL)
        ) STORED COMMENT '仅未处理行的业务键（生成列；handled/ignored 为 NULL，唯一索引允许多 NULL → 历史可共存）',
    ADD UNIQUE KEY uk_todo_item_active (active_uk);
