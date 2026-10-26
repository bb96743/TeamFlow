-- M04 交付后修复（V18）：team_member 唯一键由「终身唯一」改为「仅未移出且未删除唯一」
--
-- 背景（M1 验收遗留 L-2）：
--   V16 的唯一键 uk_team_member_team_user (team_id, user_id) 是**全表唯一**，
--   成员移出（leave_time 有值）后重新加入只能 UPDATE 覆盖原行 → 丢失上一段任职记录，
--   与 M04 RULE R3「移出不删记录，保留历史归属，便于绩效追溯」冲突。
--
-- 修复（参照 M10 V15 的生成列方案；MySQL 8 不支持部分索引）：
--   active_uk 仅在「未移出（leave_time IS NULL）且未删除（deleted = 0）」时有值，
--   其余行一律 NULL；唯一索引允许多个 NULL → 历史行可共存、互不冲突，在册行仍唯一。
--
-- 配套代码：TeamMemberService#add / #setOwner 重新加入时**新建一行**（不再 revive 覆盖）；
--   TeamMemberMapper#selectByTeamAndUser 改为「在册行优先、最新行优先」，
--   避免 V18 后同 (team_id, user_id) 多行时误判。
--
-- 说明：本脚本为 V18 追加脚本（G-4 规则：只追加 V18+，禁改 V1–V17）。
-- 幂等：由 Flyway 版本保证只执行一次；脚本内先删旧唯一键再加生成列与新唯一键。

ALTER TABLE team_member DROP INDEX uk_team_member_team_user;

ALTER TABLE team_member
    ADD COLUMN active_uk VARCHAR(200)
        GENERATED ALWAYS AS (
            IF(leave_time IS NULL AND deleted = 0,
               CONCAT(team_id, '|', user_id),
               NULL)
        ) STORED COMMENT '仅未移出且未删除行的业务键（生成列；历史行 NULL，唯一索引允许多 NULL → 历史可共存）',
    ADD UNIQUE KEY uk_team_member_active (active_uk),
    ADD INDEX idx_team_member_team_user (team_id, user_id);
