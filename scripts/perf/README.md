# M5-A 性能压测脚本（Node 内置模块，无第三方依赖）

## 为什么自研

本机没有 jmeter / k6 / ab / wrk / vegeta / artillery，Node v24 可用；本目录用 Node 内置
`http`/`https` 实现固定并发压测，输出 QPS、P50/P95/P99、成功率与错误分布。

## 目录

| 文件 | 用途 |
| --- | --- |
| `run.mjs` | 压测主脚本（自动登录取 token、逐接口压测、输出表格/JSON） |
| `seed.sql` | 性能数据集（1000 任务 / 200 目标+400KR / 200 知识页 / 20 绩效结果 / 20 用户） |
| `cleanup.sql` | 按 `PERF-` / `perf_` 标记清理数据 |

## 快速开始（推荐：独立性能库，不污染 dev/test）

```bash
# 1. 建独立性能库（仅第一次）
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS teamflow_perf CHARACTER SET utf8mb4;"

# 2. 起后端指向性能库（Flyway 自动建表到最新版本；端口避开 8080）
set DB_NAME=teamflow_perf
set JWT_SECRET=<从 dev-local/.env.local 读取>
set DB_PASSWORD=<从 dev-local/.env.local 读取>
java -jar teamflow-system/teamflow-server/target/teamflow-server-0.1.0-SNAPSHOT.jar --server.port=8081

# 3. 基线压测（空业务库；绩效接口需先有周期，脚本会自动跳过并提示）
node scripts/perf/run.mjs --base http://localhost:8081 --concurrency 20 --duration 10 \
  --out scripts/perf/results/baseline.json

# 4. 造数 + 加压后压测
mysql -uroot -p teamflow_perf < scripts/perf/seed.sql
node scripts/perf/run.mjs --base http://localhost:8081 --concurrency 20 --duration 10 \
  --only task-list,okr-list,perf-results,knowledge-search,insight-overview,task-create \
  --out scripts/perf/results/loaded.json

# 5. 清理（或直接 DROP DATABASE teamflow_perf）
mysql -uroot -p teamflow_perf < scripts/perf/cleanup.sql
```

## 参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--base` | `http://localhost:8080` | 目标服务地址 |
| `--concurrency` | 20 | 全局并发（建任务接口固定 min(并发,2)） |
| `--duration` | 10 | 每接口正式压测秒数 |
| `--warmup` | 2 | 每接口预热秒数（不计入结果；写接口为 0） |
| `--timeout` | 15000 | 单请求超时（ms） |
| `--username/--password` | admin/admin123 | 自动登录用；也可 `--token` 直接传 JWT |
| `--only` | 全部 | 只压指定接口，逗号分隔：`task-list,okr-list,...` |
| `--cycle-id` | 自动发现 | 绩效结果接口所需周期；自动取 `/api/perf/cycles` 第一条 |
| `--assignee-id` | 空 | 建任务时指定责任人（为空则入待认领池） |
| `--keyword` | 性能测试 | 知识检索关键词 |
| `--out` | 无 | 原始结果 JSON 输出路径 |
| `--json` | false | 只输出 JSON（便于报告引用） |

## 接口清单

| key | 接口 | 说明 |
| --- | --- | --- |
| `task-list` | `GET /api/task/tasks?pageNum=1&pageSize=10` | 任务列表（`@DataScope`） |
| `okr-list` | `GET /api/okr/objectives?pageNum=1&pageSize=10` | OKR 目标列表 |
| `perf-results` | `GET /api/perf/results?pageNum=1&pageSize=10&cycleId=` | 绩效结果列表；无周期时跳过并在结果中标注 |
| `knowledge-search` | `GET /api/knowledge/search?keyword=` | 全文检索（M07 实现为 GET） |
| `insight-overview` | `GET /api/insight/tasks/overview?startDate=&endDate=` | 洞察聚合读模型（逐成员经 M03/M02 取数） |
| `task-create` | `POST /api/task/tasks` | 写接口，低并发（2），标题 `PERF-<runId>-<n>` |

> 注：任务说明中的 `POST /api/knowledge/search` 与实现不一致，实际后端为 `GET /api/knowledge/search`
>（M07 `KnowledgeSearchController`），压测按真实实现执行并在报告中说明。

## 指标口径

- **QPS** = 正式压测阶段请求数 / 该阶段实际耗时（含 worker 收尾，偏保守）。
- **P50/P95/P99** = 正式压测阶段全部请求延迟分位数（线性插值）。
- **成功率** = HTTP 2xx 请求数 / 总请求数；非 2xx 与网络错误分别进入错误分布。
- 每接口顺序压测并冷却 1.2s，避免相互干扰；预热请求不计入统计。
