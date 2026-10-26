# TeamFlow 升级与回滚（传统部署）

> 适用：`deploy/traditional/` 路径（JAR + 本机 MySQL/Redis）。
> 核心前提：**Flyway 在后端启动时自动迁移**（`V1→V39`），迁移失败则应用启动失败（不带病运行）。

## 一、升级前必做

1. **备份数据库**（回滚的唯一可靠依据）：

   ```powershell
   powershell -ExecutionPolicy Bypass -File deploy/traditional/backup.ps1 -DbName teamflow
   # 记录输出中的备份路径（deploy/backup/teamflow-teamflow-<时间戳>.sql）
   ```

2. **保留上一版 JAR**（重命名归档，便于快速回滚）：

   ```powershell
   Copy-Item teamflow-system\teamflow-server\target\teamflow-server-0.1.0-SNAPSHOT.jar `
             deploy\backup\teamflow-server-<当前版本>.jar
   ```

3. **记录当前迁移版本**（升级后对照）：

   ```sql
   SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE version IS NOT NULL;
   ```

## 二、升级步骤（传统部署）

```powershell
# 1) 构建新版本（或取得新 JAR）
cd D:\TeamFlowObj\teamflow-system
mvn -B -DskipTests clean package

# 2) 停止旧实例
powershell -ExecutionPolicy Bypass -File ..\deploy\traditional\stop.ps1

# 3) 启动新实例（Flyway 自动执行新增迁移；迁移失败则启动失败 → 不要强推）
powershell -ExecutionPolicy Bypass -File ..\deploy\traditional\start.ps1 -Background

# 4) 验证
#    - 日志出现：Successfully applied N migrations / Schema `teamflow` is up to date
#    - GET /api/health 返回 200（start.ps1 已自动轮询）
#    - 登录 → 打开主要页面 → 抽查核心接口
```

Linux：

```bash
cd /opt/teamflow/teamflow-system && mvn -B -DskipTests clean package
bash ../deploy/traditional/stop.sh
bash ../deploy/traditional/start.sh
```

## 三、回滚

> **Flyway 迁移是「向前」的：社区版没有自动 undo。** 升级后若需要回滚，必须区分两类情况：

| 情况 | 处理方式 |
| --- | --- |
| **新版本尚未成功启动 / 迁移未执行** | 直接换回旧 JAR + 启动即可（数据库未变） |
| **新版本已执行了迁移（DDL 已生效）** | **用升级前的备份恢复数据库**，再换回旧 JAR 启动（见下）。不要手工删表/改 `flyway_schema_history` |

恢复步骤（会覆盖当前库）：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\traditional\stop.ps1
powershell -ExecutionPolicy Bypass -File deploy\traditional\restore.ps1 `
    -File deploy\backup\teamflow-teamflow-<升级前时间戳>.sql `
    -DbName teamflow -Force -Drop
# 换回旧 JAR 后启动
Copy-Item deploy\backup\teamflow-server-<当前版本>.jar teamflow-system\teamflow-server\target\teamflow-server-0.1.0-SNAPSHOT.jar -Force
powershell -ExecutionPolicy Bypass -File deploy\traditional\start.ps1 -Background
```

## 四、约定与注意事项

1. **禁止修改已发布的 `V*.sql`**（Flyway 校验 checksum，改动会导致启动失败；见 M01 RULE R4）。新增变更一律追加 `V40+`。
2. **禁止手工执行 `flyway:baseline` 到空库**（会让 V1/V2 被跳过、缺表；空库应直接启动由 Flyway 全量迁移）。
3. `spring.flyway.clean-disabled=true`（默认）——`flyway:clean` 不可用，防止误清库。
4. **迁移脚本包含 DDL 时，回滚脚本需随升级包一起交付**（社区版无自动 undo；本仓库暂无 `rollback/` 目录，属已知缺口）。
5. 升级窗口内建议短暂停服（单实例）；多实例部署时**先让一个实例完成迁移**再启动其余实例（或临时 `spring.flyway.enabled=false` 手工迁移）。
6. **迁移失败排查顺序**：看 `deploy/logs/backend-*.log` 中 Flyway 报错 → 校验库中 `flyway_schema_history` 末条 `success=0` 的迁移 → 修复数据/脚本后重试（**不要**直接改历史表，除非明确知道后果并已备份）。
7. 升级后抽查清单：登录、`/api/health`、任务/目标/绩效列表、文件上传（如启用）、前端页面加载。
