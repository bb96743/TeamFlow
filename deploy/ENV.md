# TeamFlow 部署环境变量清单

> 本文件**逐项列出代码实际读取的环境变量**（来源：`teamflow-server/src/main/resources/application.yml`、`application-dev.yml`、`JwtService`、`DbInit`）。
> **不要在本文件或任何部署文件中写入真实凭据**；生产值请放在 `deploy/.env`（已 gitignore）或宿主机环境变量/密钥管理系统中。
> 占位符写法：`<...>` 表示必须由部署者填写。

## 一、必填项（缺失会导致启动失败或功能不可用）

| 变量 | 必填 | 默认值 | 说明 / 生成方法 |
| --- | --- | --- | --- |
| `JWT_SECRET` | **是** | 无（缺失启动失败） | JWT 签名密钥，UTF-8 至少 32 字节。生成：`openssl rand -hex 32`（64 位十六进制）。**轮换密钥会使所有已签发 token 失效**（用户需重新登录） |
| `DB_PASSWORD` | **是**（生产） | `root`（仅开发默认） | MySQL 口令；容器部署时同时用于初始化 MySQL root 账号。生产必须替换为强口令 |

## 二、数据源（`application-dev.yml`）

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `DB_HOST` | 否 | `localhost` | MySQL 地址（Docker Compose 内为服务名 `mysql`） |
| `DB_PORT` | 否 | `3306` | MySQL 端口 |
| `DB_NAME` | 否 | `teamflow` | 库名。**首次启动空库时 Flyway 会自动建表**（V1→V39，90 张表）；已有库会自动基线/补迁移 |
| `DB_USERNAME` | 否 | `root` | 数据库账号（需具备建库/建表/DDL 权限，Flyway 要执行 DDL） |
| `DB_TIMEZONE` | 否 | `+08:00` | JDBC 连接与会话时区（`connectionTimeZone`） |

## 三、Redis（必需，硬依赖）

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `REDIS_HOST` | 否 | `localhost` | Redis 地址（Compose 内为 `redis`） |
| `REDIS_PORT` | 否 | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 否 | 空 | Redis 口令；Compose 中设置后会自动追加 `--requirepass` |

> Redis 是**硬依赖**（JWT 白名单 + 验证码直接注入 `StringRedisTemplate`，无内存降级）；Redis 不可用时登录与所有鉴权接口返回 401/500。

## 四、文件上传（G-1）

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `TEAMFLOW_FILE_STORAGE_PATH` | 否 | `./data/uploads` | 本地存储根目录。**生产建议绝对路径并挂载持久卷**（Compose 内固定 `/data/uploads`） |
| `TEAMFLOW_FILE_MAX_SIZE` | 否 | `20MB` | 单文件上限（同时作用于 `spring.servlet.multipart` 与业务校验） |
| `TEAMFLOW_FILE_MAX_SWALLOW` | 否 | `30MB` | Tomcat 请求体 swallow 上限。**必须大于文件上限**，否则超限请求会被容器断连（客户端只看到连接重置，而不是明确的 400） |

## 五、Web 容器

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `SERVER_PORT` | 否 | `8080` | 后端 HTTP 端口（Spring 标准属性 `server.port` 的环境变量形式） |
| `SPRING_PROFILES_ACTIVE` | 否 | `dev` | 激活 profile（`application.yml` 默认 `dev`）。部署使用 `dev`（数据源/Redis 配置齐全）；测试由 surefire 强制 `test`（见 `src/test/resources/application-test.yml`，库名 `TEST_DB_NAME` 默认 `teamflow_test`） |

## 六、M11 AI 助手（可选，默认零外部依赖）

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `AI_INTENT_RESOLVER` | 否 | `rule` | 意图识别实现：`rule`（纯规则，无外部依赖）/ `llm`（Spring AI + Ollama，失败自动回落规则） |
| `AI_LLM_ENABLED` | 否 | `false` | 是否启用 LLM 调用 |
| `OLLAMA_BASE_URL` | 否 | `http://localhost:11434` | Ollama 地址（Compose 内需指向宿主机，如 `http://host.docker.internal:11434`） |
| `OLLAMA_CHAT_MODEL` | 否 | `qwen2.5:7b` | Ollama 模型名 |
| `AI_TIMEOUT_SECONDS` | 否 | `20` | LLM 调用超时 |
| `AI_MAX_TOOL_ROUNDS` | 否 | `3` | 工具调用最大轮次 |
| `AI_MAX_CITATIONS` | 否 | `5` | RAG 最大引用数 |
| `AI_MAX_CONTEXT_CHARS` | 否 | `4000` | RAG 上下文最大字符数 |

> 默认 `resolver=rule`、`llm-enabled=false` 时**不需要 Ollama**；本机未验证真实模型推理（无模型/无 Key）。

## 七、运维工具专用（`DbInit` / 测试，不属于应用运行时）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `TEAMFLOW_SQL_DIR` | `../sql`（相对工作目录） | `DbInit` 读取迁移脚本的目录（工作目录为 `teamflow-system/teamflow-admin`） |
| `TEAMFLOW_DB_FIX_CONFIRM` | 空 | `DbInit fix` 的显式确认（`true`），另有 `--force` 参数；`SPRING_PROFILES_ACTIVE`/`TEAMFLOW_PROFILE` 含 `prod` 时一律拒绝 |
| `TEST_DB_NAME` | `teamflow_test` | 仅测试 profile 使用：集成测试库名（与开发库隔离） |

## 八、部署前自检清单

```powershell
# Windows PowerShell
$env:JWT_SECRET = (openssl rand -hex 32)   # 或手动生成 64 位十六进制
$env:DB_PASSWORD = '<强口令>'
$env:JWT_SECRET.Length   # 应 >= 32
```

- [ ] `JWT_SECRET` 已设置且 ≥32 字节
- [ ] `DB_PASSWORD` 为强口令（非 `root`）
- [ ] MySQL 与 Redis 可达（Compose 用健康检查保证顺序）
- [ ] `TEAMFLOW_FILE_STORAGE_PATH` 指向持久卷目录
- [ ] `TEAMFLOW_FILE_MAX_SWALLOW` > `TEAMFLOW_FILE_MAX_SIZE`
- [ ] 部署文件与 `.env` 权限收紧（仅运维可读）；**不要把 `.env` 提交到仓库**
