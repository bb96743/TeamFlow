# TeamFlow 私有化部署

> 目标：在客户/自有机房以**单机私有化**方式部署「前端 + 后端 + MySQL + Redis」。
> 技术栈：Spring Boot 4.0.8（JDK 21）+ MySQL 8.0 + Redis 5+ + Vue 3 静态产物。

---

## 一、两条部署路径怎么选

| 路径 | 适用场景 | 状态 |
| --- | --- | --- |
| **Docker Compose**（`deploy/docker-compose.yml`） | 生产首选：环境一致、依赖隔离、一条命令拉起全套；宿主机只需 Docker 20.10+ / Compose v2 | ⚠️ **本机无 Docker，未经过实际运行验证**（文件已就绪，首次使用请按 §五 清单核对） |
| **传统部署**（`deploy/traditional/`） | 客户环境已有 MySQL/Redis、或无 Docker/无法用容器；JAR + 系统服务 | ✅ **已在本机端到端实测**（空库迁移、健康检查、冒烟、前端托管、备份恢复，见 §五） |

> 两者使用同一份后端 JAR 与同一套环境变量（`deploy/ENV.md`）。

---

## 二、目录结构

```
deploy/
├── README.md                  # 本文件：选择建议、前置条件、步骤、FAQ、验证清单
├── ENV.md                     # 环境变量逐项清单（必填/默认/生成方法）
├── .env.example               # Compose 环境变量模板（复制为 .env 后填写）
├── docker-compose.yml         # MySQL + Redis + 后端 + 前端（Nginx）
├── .gitignore                 # 忽略 .env / logs / backup
├── docker/
│   ├── backend.Dockerfile     # eclipse-temurin:21-jre-alpine + JAR
│   ├── frontend.Dockerfile    # nginx:1.27-alpine + dist
│   └── nginx.conf             # 静态托管 + /api 反代 + SPA 回退
└── traditional/
    ├── start.ps1 / start.bat  # Windows 启动（后台/前台、健康检查、日志、PID）
    ├── start.sh               # Linux 启动（nohup + 健康检查）
    ├── stop.ps1 / stop.sh     # 按 PID 停止
    ├── backup.ps1 / backup.sh # mysqldump 备份（校验 + 轮转）
    ├── restore.ps1 / restore.sh # 恢复（-Force 必填；覆盖需 -Drop）
    └── UPGRADE.md             # 升级 / 回滚步骤与约定
```

---

## 三、前置条件

| 项 | 要求 | 说明 |
| --- | --- | --- |
| JDK | **21**（构建目标 17，运行 21 已验证） | 传统部署必需；Docker 镜像内已自带 |
| Maven | 3.9+ | 仅构建 JAR 需要 |
| Node.js | 22+（本机 24 实测） | 仅构建前端 `dist/` 需要 |
| MySQL | **8.0**（8.0.42 实测） | 需 `utf8mb4`；Flyway 需要 DDL 权限 |
| Redis | **5+**（5.0.14.1 实测 / Compose 用 7） | **硬依赖**：JWT 白名单 + 验证码，无内存降级 |
| Docker | 20.10+ / Compose v2 | 仅 Docker 路径需要 |
| 磁盘 | 库 + 上传文件 + 日志 | 上传目录与备份目录需持久化 |

---

## 四、快速开始

### 4.1 Docker Compose（生产首选，⚠️ 未验证）

```bash
# 0) 构建两份产物（镜像内直接 COPY）
cd teamflow-system && mvn -B -DskipTests clean package && cd ..
cd teamflow-vue && npm ci && npm run build && cd ..

# 1) 准备环境变量（JWT_SECRET / DB_PASSWORD 必填）
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env：JWT_SECRET=$(openssl rand -hex 32)、DB_PASSWORD=<强口令>

# 2) 启动（首次会自动建库 + Flyway 迁移 V1→V39）
cd deploy
docker compose --env-file .env up -d --build
docker compose ps
docker compose logs -f backend

# 3) 访问
#   前端：http://<host>:8080
#   健康：http://<host>:8080/api/health
#   初始账号：admin / admin123（V2 种子；生产务必首次登录后改密）
```

> 容器内数据卷：`mysql-data`（数据库）、`redis-data`、`teamflow-files`（上传文件）、`teamflow-logs`。
> 备份：`docker compose exec mysql mysqldump -uroot -p"$DB_PASSWORD" --single-transaction teamflow > backup.sql`

### 4.2 传统部署（✅ 本机已实测）

```powershell
# 1) 构建
cd D:\TeamFlowObj\teamflow-system
mvn -B -DskipTests clean package
cd D:\TeamFlowObj\teamflow-vue
npm ci ; npm run build          # 产出 dist/（静态托管用）

# 2) 准备环境变量（可写入 dev-local/.env.local 或系统环境变量）
#    JWT_SECRET=<64 位十六进制>；DB_PASSWORD=<强口令>；DB_HOST/DB_PORT/DB_NAME 等按需
#    详见 deploy/ENV.md

# 3) 启动（后台 + 健康检查 + 日志 + PID）
powershell -ExecutionPolicy Bypass -File D:\TeamFlowObj\deploy\traditional\start.ps1 -Background

# 4) 访问
#   http://localhost:8080/api/health   -> {"code":200,...,"data":{"status":"UP"}}
#   前端 dist 用任意静态服务器托管（生产建议 Nginx，参考 deploy/docker/nginx.conf；本机可用 python -m http.server）
```

Linux：

```bash
cd /opt/teamflow/teamflow-system && mvn -B -DskipTests clean package
export JWT_SECRET=$(openssl rand -hex 32) DB_PASSWORD='<强口令>'
bash /opt/teamflow/deploy/traditional/start.sh
```

---

## 五、验证清单（诚实标注）

### ✅ 已验证（本机，2026-10-27，传统部署路径，实测输出见 CR-20261027-13 §四）

| 验证项 | 结果（实测值） |
| --- | --- |
| 空库启动自动迁移 | 新建 `teamflow_deploy_check` 空库 → 启动 → `Successfully applied 39 migrations ... now at version v39`（4.888s）→ **90 张表**、`flyway_max=39`（39 条记录） |
| 启动与健康检查 | `Started TeamFlowApplication in 42.498 seconds`；`GET /api/health` → `{"code":200,...,"status":"UP"}`（部署脚本 15s 内轮询到 UP） |
| 核心接口冒烟 | 验证码+登录 → `code=200`（token 248 字符）→ `GET /api/task/tasks` `code=200 total=0` → `GET /api/okr/objectives` `code=200 total=0`（空库无业务数据属预期） |
| 前端 dist 静态托管 | 本机以临时静态 HTTP 服务托管 `teamflow-vue/dist`：首页 **HTTP 200（1112 bytes）**、含 `<div id="app">`；主资源 `/assets/index-*.js` **HTTP 200** |
| 备份 → 删库 → 恢复 | `backup.ps1` 生成 dump **252,013 bytes**（0.24MB）→ `DROP DATABASE`（库消失）→ `restore.ps1 -Drop -Force` → **90 表 / flyway 39 / sys_user 3 / sys_menu 269 / sys_role_menu 506**，与备份前快照 **完全一致（IDENTICAL）** |
| 恢复后库可正常启动 | 用恢复出的库重启后端：Flyway `Schema teamflow_deploy_check is up to date. No migration necessary.` → 启动完成 → 登录 `code=200` |
| 六模块构建 | `mvn -B -DskipTests clean package` 六模块 SUCCESS；`teamflow-server` 可执行 JAR **62.5 MB** |

### ⚠️ 未验证（必须如实告知）

| 项 | 原因 |
| --- | --- |
| **Docker Compose 全路径** | 开发机**未安装 Docker / Docker Compose / Nginx**，`docker-compose.yml` 与两个 Dockerfile **未经实际构建/运行** |
| Nginx 反向代理 `/api` | 本机无 Nginx；仅以静态服务器验证了 `dist` 可打开，**反代与 SPA 回退未实测** |
| Linux 启动/备份脚本 | 无 Linux 环境；脚本已做静态审查（`bash -n` 语法未能在本机执行）但未运行 |
| Redis 口令模式 | 本机 Redis 无口令；`REDIS_PASSWORD` 非空路径未实测 |
| AI LLM（Ollama）模式 | 无模型；默认 `resolver=rule` 路径可用，`llm` 路径未实测 |
| HTTPS / 域名 / 反向代理超时 | 无对应环境 |

> **结论**：传统部署路径已端到端验证；Docker 路径为**未经运行验证的参考实现**，首次部署请先在测试环境按本清单逐项核对。

---

## 六、常见问题（FAQ）

| 现象 | 原因 / 处理 |
| --- | --- |
| 启动失败：`JWT_SECRET` 相关 | 未设置或长度 <32 字节；`export JWT_SECRET=$(openssl rand -hex 32)` |
| 启动失败：Hikari 连接超时 | MySQL 不可达或口令错；检查 `DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD` 与网络/防火墙 |
| 启动失败：Flyway 报 checksum mismatch | 有人改了已发布的 `V*.sql`；恢复该脚本原始内容（禁止修改已发布脚本） |
| 启动失败：Flyway 迁移失败 | 看 `deploy/logs/backend-*.log`；修复后重试；必要时用备份恢复（见 `UPGRADE.md`） |
| 登录 401 / 验证码 500 | Redis 不可达；检查 `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD` 与 Redis 进程 |
| 上传大文件报“连接重置” | `TEAMFLOW_FILE_MAX_SWALLOW` 未大于 `TEAMFLOW_FILE_MAX_SIZE`；两者需同调 |
| 前端刷新子路由 404 | 静态服务器未配置 SPA 回退；Nginx 参考 `deploy/docker/nginx.conf` 的 `try_files ... /index.html` |
| 首次登录账号 | `V2` 种子：`admin/admin123`、`chensy/admin123`、`wumin/admin123`；**生产环境首次登录后立即改密并删除演示账号** |
| 端口占用 | 改 `SERVER_PORT`（启动脚本 `-Port` 参数或环境变量）与 `WEB_PORT`（Compose） |

---

## 七、安全清单（生产必做）

- [ ] `JWT_SECRET` 为随机 64 位十六进制，且不入库/不提交；轮换即全员重新登录
- [ ] MySQL 使用**非 root** 专用账号（具备业务库 DDL 权限即可），口令强随机
- [ ] Redis 启用 `requirepass`（或仅监听内网/Unix socket）
- [ ] 仅暴露前端端口（80/443）与必要的运维端口；MySQL/Redis/后端不直接暴露公网
- [ ] `deploy/.env` 权限收紧（仅运维可读），已被 `.gitignore` 忽略
- [ ] 首次登录后修改 `admin` 密码、停用/删除演示账号
- [ ] 配置定期备份（`deploy/traditional/backup.ps1` + 异地保存），并**演练恢复**
- [ ] 上传目录与备份目录在持久化卷上，且容量监控
- [ ] 生产建议 HTTPS（反向代理终止 TLS）；`/v3/api-docs` 等文档端点可关闭
