# 汇流 TeamFlow

> 以 **OKR 与项目** 为主线、**任务与流程** 为执行载体、**绩效自动归集** 为结果闭环、**AI 助手** 为统一入口的团队协作与目标管理平台，支持私有化部署与对外能力开放。

![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.8-6DB33F?logo=springboot&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178C6?logo=typescript&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-5%2B-DC382D?logo=redis&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow)

---

## 一、这个项目解决什么问题

团队协作工具长期存在三类断裂，导致「工具很多、协同很少」：

| 断裂 | 具体表现 | 业务后果 |
| --- | --- | --- |
| **目标与执行断裂** | 有任务看板，但没有目标体系；任务做完不知道服务哪个目标 | 绩效只能人工主观打分，管理动作无法回溯 |
| **执行与沉淀断裂** | 任务、会议、审批都在流转，但过程内容无处存放 | 流程节点沦为「空壳审批」，经验无法复用 |
| **数据与决策断裂** | 各模块各有一套数字，口径不一致 | 看板与业务台账对不上，不敢用于决策 |

TeamFlow 用一条可验证的价值链把这些问题串起来：

```
设定目标 → 拆解为项目/任务 → 执行与协作留痕 → 数据自动归集 → 绩效与复盘 → 反哺下一周期目标
```

五条设计原则贯穿全站：

| 原则 | 落地方式 |
| --- | --- |
| **单一数据源** | 用户/部门只在平台底座维护；知识正文只在知识库存储；指标口径只在统计模块登记 |
| **模块自治** | 每个模块独占表前缀、接口前缀、权限码；跨模块只走接口，不直连业务表 |
| **事件驱动** | 任务完成、审批通过等以领域事件广播，下游订阅而非反向调用 |
| **默认安全** | 无状态令牌 + 按钮级权限 + 数据范围过滤 + 写操作审计，四层叠加 |
| **私有化友好** | 无强制云依赖，MySQL + Redis + 单 JAR 即可运行，支持离线环境交付 |

---

## 二、当前状态（先读这一节）

> **M0 与 M1 已完成**：平台底座可用，**M1 主链路五模块（目标 → 任务 → 绩效 → 个人 → 团队）后端与前端均已交付**，界面可操作、数据可跑通。M2–M4 业务域尚未开工，请勿把「规划中」当作「已具备」。

| 里程碑 | 主题 | 范围 | 状态 |
| --- | --- | --- | --- |
| **M0** | 工程就绪 | M00 平台底座、M01 工程与交付 | ✅ **已完成** |
| **M1** | 主链路闭环 | M02 目标、M03 任务、M04 团队、M05 绩效、M10 个人效率 | ✅ **已完成**：5 模块后端 99 接口 + 前端 18 页 |
| **M2** | 数据可见 | M08 招聘、M13 统计、M14 项目（立项） | 🔜 下一步 |
| **M3** | 协同沉淀 | M06 会议、M07 知识、M09 消息、M14（甘特/风险/复盘） | ⬜ 未开始 |
| **M4** | AI 与开放 | M11 AI 助手、M12 集成与开放 | ⬜ 未开始 |
| **M5** | 打磨交付 | 全模块 | ⬜ 未开始 |

**当前规模**：45 张表（`V1`–`V17` 版本化迁移）· 后端 **99 个业务接口** · 前端 **18 个业务页面** · 后端 **132** 个测试用例 · 前端 **21** 个测试用例 · GitHub Actions 自动门禁。

**已经可以做的**（登录后即可验证）：

**平台能力（M00）**

- 账号密码登录、图形验证码、JWT 签发校验、登出
- 部门 / 岗位 / 用户 / 角色 / 菜单 五类组织与权限管理
- 菜单与按钮级权限、五档数据范围过滤（全部 / 本部门 / 本部门及下级 / 自定义 / 仅本人）
- 数据字典与系统参数（运行期可改，无需发版）
- 操作日志与登录日志（查询、统计、趋势、泳道、热力图、导出）
- 统一文件上传（后缀白名单、20MB 上限、引用计数、鉴权下载）
- 代码生成器（导入表结构 → 预览 7 个文件 → 打包下载）
- 工作台首页（数据概览、趋势、活动流）

**M1 主链路（M02 / M03 / M04 / M05 / M10）**

- **目标与 OKR**：周期管理、三层目标（公司→部门→个人）、KR 拆解、进度自动回写、对齐度检查
- **任务与工作流**：任务派发与认领池、集中状态机（迁移规则取后端数据）、流转轨迹、线性单节点审批
- **绩效**：周期与指标模板、指标字典四要素、自动归集（可下钻追溯 KR 与任务）、自评/上级评、归档锁定、申诉
- **个人效率**：待办收件箱（统一投递 + 处理回写原模块）、工时（强制关联任务）、复盘、效能画像
- **团队与成员**：团队 CRUD、成员关系与团队内角色、成员负载视图

> **贯穿主链路的闭环**：建目标 → 拆 KR → 派任务 → 完成任务后 **KR 进度自动回写** → 绩效周期 **自动归集出分数**，全程无手工补录。

**尚未实现的**（M2–M4 业务域）：

- 招聘（M08）、统计洞察（M13）、项目管理（M14）、会议（M06）、知识库（M07）、消息（M09）、AI 助手（M11）、集成开放（M12）
- 这些模块的导航入口已建（显示「规划中」占位页），**后端零接口**

---

## 三、功能全景

产品规划 **15 个功能模块**，按六个业务簇组织：

| 业务簇 | 模块 | 职责 | 里程碑 | 状态 |
| --- | --- | --- | --- | --- |
| **平台与工程** | `M00` 平台底座 | 认证、权限、组织、字典、参数、日志、文件、代码生成 | M0 | ✅ 已实现 |
| | `M01` 工程与交付 | 技术栈治理、构建、环境配置、数据初始化、发布 | M0 | ✅ 已完成 |
| **目标与执行** | `M02` 目标与 OKR | 目标周期、KR 拆解、进度自动回写、对齐度检查 | M1 | ✅ 已实现（23 接口 / 4 页） |
| | `M14` 项目管理 | 项目立项、里程碑、甘特、风险登记、复盘 | M2 | ⬜ 规划中 |
| | `M03` 任务与工作流 | 任务派发跟踪、状态机编排、流程定义与审批 | M1 | ✅ 已实现（19 接口 / 4 页） |
| | `M04` 团队与成员 | 团队/项目组关系、角色分工、成员负载 | M1 | ✅ 已实现（11 接口 / 2 页） |
| | `M05` 绩效 | 周期、指标、评分校准、结果归档（数据来自 M02/M03） | M1 | ✅ 已实现（28 接口 / 4 页） |
| **协作与知识** | `M06` 日程与会议 | 日历、会议室预定、会议纪要 | M3 | ⬜ 规划中 |
| | `M07` 知识与文档 | Wiki、文档中心、全文检索、SOP、决策记录 | M3 | ⬜ 规划中 |
| | `M09` 协作与消息 | 站内信、@提及、动态流、公告、外部触达 | M3 | ⬜ 规划中 |
| | `M10` 个人效率 | 待办收件箱、工时、专注、复盘、效能画像 | M1 | ✅ 已实现（18 接口 / 4 页） |
| **人才招聘** | `M08` 招聘 | 职位、候选人、面试、Offer、简历筛查 | M2 | ⬜ 规划中 |
| **AI 与开放** | `M11` AI 助手 | 对话入口、工具路由、RAG 问答、日报生成 | M4 | ⬜ 规划中 |
| | `M12` 集成与开放 | 第三方集成、MCP Server、开放 API、Webhook | M4 | ⬜ 规划中 |
| **数据洞察** | `M13` 统计与洞察 | 跨域指标看板、任务统计、团队作战室（纯读模型） | M2 | ⬜ 规划中 |

> 每个模块的职责边界、"明确不做"、数据归属与验收标准，见该模块的 `RULE.md`（项目内部文档）。

---

## 四、技术栈

### 4.1 已落地（版本取自运行中产物实测）

**后端**（`teamflow-system`，Maven 六模块）

| 层 | 选型 | 实际版本 |
| --- | --- | --- |
| 框架 | Spring Boot | **4.0.8** |
| 语言 | Java | 编译目标 **17**（构建于 JDK 21） |
| JSON | Jackson | **3.1.5**（`tools.jackson.*`） |
| 持久层 | MyBatis-Plus | **3.5.17**（boot4 starter；MyBatis 3.5.19 / mybatis-spring 4.0.0） |
| 数据库 | MySQL Connector/J | **9.7.0** |
| 认证 | JJWT | **0.12.6**（gson 实现） |
| 接口文档 | springdoc + Knife4j Next | **3.0.3** / **5.7.9** |
| Web 容器 | Tomcat | **11.0.24** |
| 会话与缓存 | Spring Data Redis | 4.0.8 |
| 构建 | Maven | 3.9+ |

**前端**（`teamflow-vue`）

| 层 | 选型 | 实际版本 |
| --- | --- | --- |
| 框架 | Vue | **3.5.35** |
| 路由 | vue-router | **4.6.4** |
| 语言 | TypeScript | **5.7.3** |
| 构建 | Vite | **6.4.3** |
| 动效 | GSAP | **3.15.0** |
| UI | **自研组件库** | 27 个 UI 组件 + 20 个业务组件 + 6 个 SVG 图表 + 6 个布局组件 |
| 状态 | Composables | 14 个组合式函数 |
| 样式 | 七层 CSS 分层 | 令牌 / 基础 / 材质 / 控件 / 仪器 / 布局 / 页面 |

> **前端刻意不引入** Element Plus、Pinia、axios、图表库、i18n —— 体积与视觉完全自控，主题（明/暗）与密度靠 CSS 变量切换。

**基础设施**：MySQL 8.0+ / Redis 5+

### 4.2 规划中（尚未引入代码）

以下为产品目标形态的选型，**当前 `pom.xml` 中不存在对应依赖**，将在对应里程碑引入：

| 能力 | 计划选型 | 拟引入里程碑 |
| --- | --- | --- |
| AI 对话 / RAG / 工具调用 | Spring AI 2.0（`ChatClient` + `@Tool`） | M4（M11） |
| 流程引擎（复杂审批） | Flowable 8（BPMN） | 待决策（M03） |
| 对象转换 | easy-trans | 待定 |
| 容器编排 | Docker Compose | M5 |

### 4.3 几个必须知道的技术约束

1. **JSON 库是 Jackson 3**（`tools.jackson.*`）。按 Jackson 2（`com.fasterxml.jackson`）写新代码会编译失败。注意 classpath 中仍存在 springdoc 传递的 Jackson 2，**用错 import 能编译通过但运行期拿不到 Bean**。
2. **MyBatis-Plus 仅作为 starter 引入**，项目实际使用注解式 MyBatis + 手写 SQL（分页为 `LIMIT`）。新增代码请沿用这一风格。
3. **Redis 是硬依赖**，不是可选缓存：JWT 令牌白名单与验证码都存在 Redis，无降级实现。
4. **无数据库迁移工具**：`V1__init_schema.sql` / `V2__seed_data.sql` 由 `DbInit` 工具手工执行，无版本记录表。

---

## 五、系统架构

```
┌──────────────────────────────────────────────────────────────┐
│  前端 teamflow-vue · Vue 3 + TS + 自研组件库 + SVG 图表      │
└───────────────────────────┬──────────────────────────────────┘
                            │ /api（开发态 Vite 代理）
┌───────────────────────────▼──────────────────────────────────┐
│  接入层   统一响应 R · 统一异常 · TraceId 链路号 · CORS       │
├──────────────────────────────────────────────────────────────┤
│  安全层   JWT 认证 → 按钮权限 → 数据范围过滤 → 写操作审计     │
├──────────────────────────────────────────────────────────────┤
│  业务层   M00 平台底座（已实现）                             │
│           M02–M14（按里程碑推进）                            │
├──────────────────────────────────────────────────────────────┤
│  领域能力 代码生成 · 字典 · 参数 · 文件表（上传待补）         │
├──────────────────────────────────────────────────────────────┤
│  存储层   MySQL（17 张平台表） · Redis（令牌 / 验证码）       │
└──────────────────────────────────────────────────────────────┘
```

**后端六模块**（依赖方向单向，不允许循环引用）：

| 模块 | 职责 |
| --- | --- |
| `teamflow-common` | 统一响应 `R`、分页 `PageQuery`/`PageResult`、业务异常 |
| `teamflow-framework` | JWT 认证、`@RequiresPermission`、`@DataScope`、`@OperLog`、全局异常、TraceId |
| `teamflow-system` | 平台业务：认证、用户/角色/菜单/部门/岗位/字典/参数、看板、监控 |
| `teamflow-generator` | 代码生成（表导入、字段配置、预览、ZIP 下载） |
| `teamflow-admin` | 健康检查、`DbInit` 建库工具 |
| `teamflow-server` | 启动类、运行时配置、接口文档、可执行包 |

**跨模块三条硬规则**（新增业务模块必须遵守）：

1. **只读引用，不复制** —— 需要用户/部门/字典时读 `sys_*`；需要其他业务数据时走对方 Service。
2. **禁止跨模块直连业务表** —— 除 `sys_user` / `sys_dept` / `sys_dict_*` 外，业务表只能由归属模块访问。
3. **投递不代劳** —— 消息与待办由归属模块投递到统一网关，不替对方建表。

---

## 六、快速开始

### 6.1 环境要求

| 依赖 | 要求 | 本项目验证环境 |
| --- | --- | --- |
| JDK | 17+ | 21.0.12.1 |
| Maven | 3.6+ | 3.9.16 |
| Node.js | 22+ | 24.9.0 |
| MySQL | 8.0+ | 8.0.42 |
| Redis | 5+ | 5.0.14.1 |

### 6.2 环境变量

后端**只读进程环境变量**，不自动加载 `.env` 文件。

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `JWT_SECRET` | JWT 签名密钥，**UTF-8 至少 32 字节** | 无（**必填**，缺失或过短会启动失败） |
| `DB_HOST` / `DB_PORT` | MySQL 地址 / 端口 | `localhost` / `3306` |
| `DB_NAME` | 数据库名 | `teamflow` |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号 / 口令 | `root` / `root` |
| `DB_TIMEZONE` | 连接与会话时区 | `+08:00` |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址 / 端口 | `localhost` / `6379` |
| `REDIS_PASSWORD` | Redis 口令 | 空 |
| `VITE_API_PROXY_TARGET` | 前端开发代理目标 | `http://localhost:8080` |

生成密钥：

```bash
export JWT_SECRET="$(openssl rand -hex 32)"
```

> 不要提交任何真实密钥或口令到版本库。`.gitignore` 已排除 `.env*`、`*.key`、`application-local.yml`。

### 6.3 初始化数据库

创建空库 `teamflow` 后按序导入两份 SQL：

```bash
mysql -u root -p --default-character-set=utf8mb4 \
      --init-command="SET time_zone='+08:00'" teamflow < teamflow-system/sql/V1__init_schema.sql
mysql -u root -p --default-character-set=utf8mb4 \
      --init-command="SET time_zone='+08:00'" teamflow < teamflow-system/sql/V2__seed_data.sql
```

也可在 IDE 运行 `com.teamflow.admin.util.DbInit`。

> ⚠️ `DbInit` 传参 `fix` 会 **DROP DATABASE**，仅在确认库名并已备份后使用。

### 6.4 启动后端

```bash
cd teamflow-system
mvn -pl teamflow-server -am package -DskipTests
java -jar teamflow-server/target/teamflow-server-0.1.0-SNAPSHOT.jar
```

服务端口 **8080**。启动成功标志：

```
Started TeamFlowApplication in 11.5 seconds
Tomcat started on port 8080 (http)
```

### 6.5 启动前端

```bash
cd teamflow-vue
npm ci
npm run dev
```

访问 <http://localhost:5173>，`/api` 由 Vite 代理到 `http://localhost:8080`。

### 6.6 验证服务

```bash
# 健康检查（匿名）
curl http://localhost:8080/api/health
# → {"code":200,"data":{"status":"UP",...}}

# 未认证访问受保护接口（应返回 401）
curl -i http://localhost:8080/api/auth/me

# 接口文档（需先登录，带 Bearer 令牌）
# http://localhost:8080/doc.html
```

**本地开发种子账号**：`admin` / `admin123` —— **仅限本地开发**，上线前必须修改，并配置独立的数据库与 Redis 凭据。

> 超级管理员可见全部启用菜单；其余种子角色默认未授予菜单，需在「角色管理」中分配。

---

## 七、项目结构

```
TeamFlow/
├── teamflow-system/                 # 后端（Maven 六模块）
│   ├── pom.xml                      # 父 POM：Spring Boot 4.0.8
│   ├── sql/
│   │   ├── V1__init_schema.sql      # 17 张平台表
│   │   └── V2__seed_data.sql        # 种子数据（部门/角色/菜单/字典/参数）
│   ├── teamflow-common/             # 统一响应、分页、异常
│   ├── teamflow-framework/          # 安全、权限、数据范围、审计、异常、TraceId
│   ├── teamflow-system/             # 平台业务：认证、系统管理、看板、监控
│   ├── teamflow-generator/          # 代码生成器
│   ├── teamflow-admin/              # 健康检查、DbInit
│   └── teamflow-server/             # 启动装配、运行时配置、可执行包
├── teamflow-vue/                    # 前端（Vue 3 + Vite + TS）
│   ├── src/api/                     # 统一 HTTP 客户端与接口定义
│   ├── src/views/                   # 14 个页面
│   ├── src/components/              # 自研组件库（ui / business / charts / layout）
│   ├── src/composables/             # 14 个组合式函数
│   ├── src/styles/                  # 七层样式
│   └── scripts/session-regression.py# Playwright 浏览器会话回归
├── demo/teamflow-proto/             # 15 模块高保真原型（静态 HTML，不随仓库发布）
├── docs/                            # 项目内部文档（不随仓库发布）
└── .gitignore
```

> `docs/` 与 `demo/` 按约定只留本地，不随仓库分发（见 `.gitignore`）。

---

## 八、质量门禁

| 门禁 | 命令 | 标准 |
| --- | --- | --- |
| 后端编译 | `mvn -DskipTests clean package` | 六模块 SUCCESS |
| 后端测试 | `mvn -pl teamflow-server -am test` | **132 个用例全绿**（需 MySQL + Redis + `JWT_SECRET`） |
| 框架级测试 | `mvn -pl teamflow-framework -am test` | 不依赖中间件，可离线跑 |
| 前端类型检查与构建 | `npm run build` | `vue-tsc --noEmit` 通过 + 打包成功 |
| 前端单测 | `npm run test:unit` | **21 个用例全绿**（可离线跑） |
| **CI（推荐）** | 推送即触发 `.github/workflows/ci.yml` | 后端 132 例 + 前端 21 例 + build，两个 job 并行；失败自动留诊断注解 |
| 本地等价 CI | `powershell -File scripts/ci-local.ps1` | 不依赖 GitHub，本机一键跑完全部门禁（约 60 秒） |
| 浏览器回归 | `python scripts/session-regression.py` | 需前后端均启动 + Playwright |

当前基线：后端 **20+ 个测试类 / 132 个用例**，前端 **9 个测试文件 / 21 个用例**。

> **CI 的额外价值**：GitHub Actions 在**全新空库**上跑测试，Flyway 需从 `V1` 迁移到 `V17` 建立 45 张表——这同时验证了「干净机器可一次性拉起」与迁移脚本的完整性（本地库是演进而来，无法覆盖该场景）。

---

## 九、接口与权限约定

| 维度 | 规则 |
| --- | --- |
| 路径 | `/api/<模块代号>/<资源>`，如 `/api/okr/objectives` |
| 权限码 | `<代号>:<资源>:<动作>`，如 `okr:objective:add`（现有 34 个） |
| 响应 | 统一返回体 `{ code, message, data, traceId }`，成功 `code = 200` |
| 分页 | 请求 `pageNum` / `pageSize`（上限 100），响应 `{ rows, total, pageNum, pageSize }` |
| 数据权限 | 注解式声明五档范围，与权限码叠加生效 |
| 审计 | 写操作记录操作人、时间、耗时、来源 IP，敏感字段脱敏 |
| 链路号 | 每次请求生成 `traceId`，贯穿日志、响应体与响应头 `X-Trace-Id` |

---

## 十、文档

项目采用三级文档治理，防止「文档与代码脱节」：

| 层级 | 内容 | 位置 |
| --- | --- | --- |
| **L0 需求总计划** | 15 模块划分、61 条需求台账、里程碑、协作协议、全局规则 | `docs/需求总计划.md` |
| **L1 模块规则** | 每个模块的职责边界、"明确不做"、数据归属、实现规则、验收标准 | `docs/modules/Mxx-*/RULE.md` |
| **L2 变更申请** | 跨模块变更的申请与审批记录 | `docs/change-requests/` |

补充文档：`DESIGN.md`（视觉与组件基线）、`docs/项目成品说明书.md`（产品全貌）、`docs/项目技术栈与启动指南.md`（环境搭建与排障）。

> 以上文档按约定**只留本地，不随仓库发布**。

---

## 十一、路线图

| 里程碑 | 主题 | 交付目标 |
| --- | --- | --- |
| **M0** ✅ | 工程就绪 | 平台底座 + 工程规范 + 文档治理 |
| **M1** ✅ | 主链路闭环 | 打通「目标 → 任务 → 绩效」：建目标 → 拆 KR → 派任务 → 任务完成自动回写 KR 进度 → 绩效自动归集，**全流程无手工补录**（已端到端验证：任务完成后 KR 进度 `progress_source=task_writeback`，绩效总分由 KR 达成率/任务完成率/时限达标率/上级评分加权算出） |
| **M2** | 数据可见 | 招聘漏斗跑通；统计口径与业务台账一致；项目可关联目标并挂载任务 |
| **M3** | 协同沉淀 | 会议纪要自动归档进知识库；知识库可全文检索；站内信与待办打通 |
| **M4** | AI 与开放 | 自然语言完成「派任务 / 查绩效」；知识问答带引用溯源；外部 AI 客户端可经 MCP 调用只读能力 |
| **M5** | 打磨交付 | 集成中心、开放 API Key 管理、性能压测、可私有化部署 |

---

## 十二、许可证

本项目代码采用 **MIT License**（见 `teamflow-system/pom.xml` 与 `teamflow-vue/package.json` 的 license 声明），允许商用、修改与分发，使用时请保留版权及许可证声明。

第三方依赖遵循各自许可证。GSAP 使用 [Standard "No Charge" License](https://gsap.com/community/standard-license/)，不属于本项目的 MIT 授权范围，使用与再分发时须遵守其条款并保留原有声明。
