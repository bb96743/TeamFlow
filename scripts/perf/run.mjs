#!/usr/bin/env node
/**
 * M5-A 轻量并发压测脚本（Node 内置 http/https，无第三方依赖）。
 *
 * 用法：
 *   node scripts/perf/run.mjs --base http://localhost:8080 --concurrency 20 --duration 10
 *   node scripts/perf/run.mjs --base http://localhost:8081 --concurrency 50 --duration 20 --only task-list,insight-overview
 *   node scripts/perf/run.mjs --base http://localhost:8080 --token <JWT> --out scripts/perf/results/latest.json
 *
 * 说明：
 *   - 默认自动登录（--username/--password，默认 admin/admin123）；也可直接给 --token；
 *   - 每个接口独立并发压测，默认并发 20、持续 10s，预热 2s 不计入结果；
 *   - 输出 QPS / P50 / P95 / P99 / 成功率 / 错误分布，可 --json 只输出 JSON；
 *   - 建任务接口为写接口，默认低并发（2），标题带本次 runId 前缀，便于清理。
 */
import http from 'node:http'
import https from 'node:https'
import fs from 'node:fs'
import path from 'node:path'

const DEFAULTS = {
  base: 'http://localhost:8080',
  concurrency: 20,
  duration: 10,
  warmup: 2,
  timeout: 15000,
  username: 'admin',
  password: 'admin123',
  keyword: '性能测试',
  cooldown: 1200,
}

function parseArgs(argv) {
  const args = { ...DEFAULTS, only: null, token: null, cycleId: null, assigneeId: null, out: null, json: false }
  for (let i = 0; i < argv.length; i += 1) {
    const raw = argv[i]
    const [flag, inline] = raw.split('=')
    const value = inline !== undefined ? inline : argv[i + 1]
    switch (flag) {
      case '--base': args.base = value; if (inline === undefined) i += 1; break
      case '--concurrency': args.concurrency = Number(value); if (inline === undefined) i += 1; break
      case '--duration': args.duration = Number(value); if (inline === undefined) i += 1; break
      case '--warmup': args.warmup = Number(value); if (inline === undefined) i += 1; break
      case '--timeout': args.timeout = Number(value); if (inline === undefined) i += 1; break
      case '--username': args.username = value; if (inline === undefined) i += 1; break
      case '--password': args.password = value; if (inline === undefined) i += 1; break
      case '--token': args.token = value; if (inline === undefined) i += 1; break
      case '--cycle-id': args.cycleId = Number(value); if (inline === undefined) i += 1; break
      case '--assignee-id': args.assigneeId = Number(value); if (inline === undefined) i += 1; break
      case '--keyword': args.keyword = value; if (inline === undefined) i += 1; break
      case '--only': args.only = value.split(',').map(item => item.trim()).filter(Boolean); if (inline === undefined) i += 1; break
      case '--out': args.out = value; if (inline === undefined) i += 1; break
      case '--json': args.json = true; break
      case '--help': args.help = true; break
      default: break
    }
  }
  return args
}

function apiUrl(base, pathname) {
  return new URL(pathname, base.endsWith('/') ? base : `${base}/`)
}

function request(base, { method = 'GET', pathname, token, body, timeout }) {
  return new Promise((resolve) => {
    const url = apiUrl(base, pathname)
    const transport = url.protocol === 'https:' ? https : http
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body))
    const headers = { Accept: 'application/json', Connection: 'keep-alive' }
    if (token) headers.Authorization = `Bearer ${token}`
    if (payload) {
      headers['Content-Type'] = 'application/json'
      headers['Content-Length'] = payload.length
    }
    const began = process.hrtime.bigint()
    const req = transport.request({
      protocol: url.protocol,
      hostname: url.hostname,
      port: url.port || (url.protocol === 'https:' ? 443 : 80),
      path: `${url.pathname}${url.search}`,
      method,
      headers,
      timeout,
    }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', chunk => { data += chunk })
      res.on('end', () => {
        const ms = Number(process.hrtime.bigint() - began) / 1e6
        let code = null
        try {
          code = data ? JSON.parse(data).code : null
        } catch {
          code = null
        }
        resolve({ ms, status: res.statusCode, code, body: data })
      })
    })
    req.on('timeout', () => req.destroy(new Error('TIMEOUT')))
    req.on('error', (error) => {
      const ms = Number(process.hrtime.bigint() - began) / 1e6
      resolve({ ms, status: 0, code: null, error: error.code || error.message, body: '' })
    })
    if (payload) req.write(payload)
    req.end()
  })
}

async function login(base, username, password, timeout) {
  const captcha = await request(base, { pathname: '/api/auth/captcha', timeout })
  const captchaData = JSON.parse(captcha.body || '{}')
  const captchaUuid = captchaData?.data?.uuid
  const captchaCode = captchaData?.data?.code
  if (!captchaUuid || !captchaCode) {
    throw new Error(`登录失败：无法获取验证码（status=${captcha.status}）`)
  }
  const loginResult = await request(base, {
    method: 'POST',
    pathname: '/api/auth/login',
    timeout,
    body: { username, password, captcha: captchaCode, captchaUuid },
  })
  const payload = JSON.parse(loginResult.body || '{}')
  if (!payload?.data?.token) {
    throw new Error(`登录失败：${payload?.message || loginResult.status}`)
  }
  return payload.data.token
}

async function discoverCycleId(base, token, timeout) {
  const result = await request(base, { pathname: '/api/perf/cycles?pageNum=1&pageSize=1', token, timeout })
  try {
    const payload = JSON.parse(result.body || '{}')
    const rows = payload?.data?.rows || []
    return rows.length ? Number(rows[0].id) : null
  } catch {
    return null
  }
}

function todayIso(offsetDays = 0) {
  const date = new Date()
  date.setDate(date.getDate() + offsetDays)
  return date.toISOString().slice(0, 10)
}

function buildEndpoints(args, ctx) {
  const endpoints = [
    {
      key: 'task-list',
      label: '任务列表 GET /api/task/tasks',
      method: 'GET',
      pathname: '/api/task/tasks?pageNum=1&pageSize=10',
    },
    {
      key: 'okr-list',
      label: 'OKR 目标 GET /api/okr/objectives',
      method: 'GET',
      pathname: '/api/okr/objectives?pageNum=1&pageSize=10',
    },
    {
      key: 'perf-results',
      label: '绩效结果 GET /api/perf/results',
      method: 'GET',
      pathname: `/api/perf/results?pageNum=1&pageSize=10${ctx.cycleId ? `&cycleId=${ctx.cycleId}` : ''}`,
      optional: true,
      skipReason: ctx.cycleId ? null : '未找到绩效周期（cycleId 为空；可先造 perf_cycle 或传 --cycle-id）',
    },
    {
      key: 'knowledge-search',
      label: '知识检索 GET /api/knowledge/search',
      // 说明：M07 实现为 GET（非 POST），压测按真实实现执行
      method: 'GET',
      pathname: `/api/knowledge/search?keyword=${encodeURIComponent(args.keyword)}`,
    },
    {
      key: 'insight-overview',
      label: '洞察聚合 GET /api/insight/tasks/overview',
      method: 'GET',
      pathname: `/api/insight/tasks/overview?startDate=${todayIso(-29)}&endDate=${todayIso(0)}`,
    },
    {
      key: 'task-create',
      label: '建任务 POST /api/task/tasks',
      method: 'POST',
      pathname: '/api/task/tasks',
      write: true,
      concurrency: Math.min(args.concurrency, 2),
      warmup: 0,
      bodyFactory: (index) => ({
        taskTitle: `PERF-${ctx.runId}-${index}`,
        priority: 'normal',
        remark: 'M5-A 压测写入，cleanup.sql 按 PERF- 前缀清理',
        ...(args.assigneeId ? { assigneeId: args.assigneeId } : {}),
      }),
    },
  ]
  if (args.only && args.only.length) {
    return endpoints.filter(endpoint => args.only.includes(endpoint.key))
  }
  return endpoints
}

function percentile(sorted, value) {
  if (!sorted.length) return null
  if (sorted.length === 1) return sorted[0]
  const position = (value / 100) * (sorted.length - 1)
  const lower = Math.floor(position)
  const upper = Math.ceil(position)
  if (lower === upper) return sorted[lower]
  return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
}

function round(value, digits = 2) {
  return value === null || value === undefined ? null : Number(value.toFixed(digits))
}

async function runEndpoint(args, endpoint) {
  if (endpoint.skipReason) {
    return { key: endpoint.key, label: endpoint.label, skipped: true, reason: endpoint.skipReason }
  }
  const concurrency = endpoint.concurrency || args.concurrency
  const durationMs = args.duration * 1000
  const warmupMs = Math.max(0, endpoint.warmup === undefined ? args.warmup : endpoint.warmup) * 1000
  const latencies = []
  const statuses = new Map()
  const errorKinds = new Map()
  let success = 0
  let failed = 0
  let counter = 0

  async function phase(workers, deadline, record) {
    await Promise.all(Array.from({ length: workers }, async () => {
      while (Date.now() < deadline) {
        counter += 1
        const body = endpoint.bodyFactory ? endpoint.bodyFactory(counter) : undefined
        const result = await request(args.base, {
          method: endpoint.method,
          pathname: endpoint.pathname,
          token: args.token,
          timeout: args.timeout,
          body,
        })
        if (!record) continue
        latencies.push(result.ms)
        const statusKey = result.status === 0
          ? `ERR:${result.error}`
          : `HTTP ${result.status}${result.code ? ` (code=${result.code})` : ''}`
        statuses.set(statusKey, (statuses.get(statusKey) || 0) + 1)
        if (result.status >= 200 && result.status < 300) {
          success += 1
        } else {
          failed += 1
          const kind = result.status === 0 ? result.error : `HTTP ${result.status}`
          errorKinds.set(kind, (errorKinds.get(kind) || 0) + 1)
        }
      }
    }))
  }

  if (warmupMs > 0) {
    await phase(Math.min(concurrency, 2), Date.now() + warmupMs, false)
  }
  const started = Date.now()
  await phase(concurrency, started + durationMs, true)
  const elapsedSeconds = Math.max(0.001, (Date.now() - started) / 1000)
  const total = latencies.length
  const sorted = [...latencies].sort((left, right) => left - right)
  return {
    key: endpoint.key,
    label: endpoint.label,
    method: endpoint.method,
    pathname: endpoint.pathname,
    write: Boolean(endpoint.write),
    concurrency,
    durationSeconds: args.duration,
    requests: total,
    success,
    failed,
    successRate: total ? round((success / total) * 100, 2) : 0,
    qps: round(total / elapsedSeconds, 2),
    p50: round(percentile(sorted, 50)),
    p95: round(percentile(sorted, 95)),
    p99: round(percentile(sorted, 99)),
    max: round(sorted.length ? sorted[sorted.length - 1] : null),
    statusDistribution: Object.fromEntries([...statuses.entries()].sort()),
    errorDistribution: Object.fromEntries([...errorKinds.entries()].sort()),
  }
}
function printTable(results) {
  const header = ['接口', '并发', '请求数', 'QPS', 'P50(ms)', 'P95(ms)', 'P99(ms)', '成功率']
  const rows = results.map(result => result.skipped
    ? [result.key, '-', 'SKIP', '-', '-', '-', '-', result.reason]
    : [result.key, result.concurrency, result.requests, result.qps, result.p50, result.p95, result.p99, `${result.successRate}%`])
  const widths = header.map((title, index) => Math.max(
    String(title).length,
    ...rows.map(row => String(row[index] ?? '').length),
  ))
  const line = values => values.map((value, index) => String(value ?? '').padEnd(widths[index])).join('  ')
  console.log(line(header))
  console.log(widths.map(width => '-'.repeat(width)).join('  '))
  rows.forEach(row => console.log(line(row)))
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help) {
    console.log('用法：node scripts/perf/run.mjs --base http://localhost:8080 --concurrency 20 --duration 10 [--only task-list,okr-list] [--out results.json] [--json]')
    return
  }
  const ctx = { runId: Date.now().toString(36) }
  if (!args.token) {
    args.token = await login(args.base, args.username, args.password, args.timeout)
  }
  if (!args.cycleId) {
    args.cycleId = await discoverCycleId(args.base, args.token, args.timeout)
  }
  ctx.cycleId = args.cycleId
  const endpoints = buildEndpoints(args, ctx)
  const results = []
  for (const endpoint of endpoints) {
    if (!args.json) process.stderr.write(`\n▶ ${endpoint.label}（并发 ${endpoint.concurrency || args.concurrency}，${args.duration}s）\n`)
    results.push(await runEndpoint(args, endpoint))
    if (args.cooldown > 0) await new Promise(resolve => setTimeout(resolve, args.cooldown))
  }
  const output = {
    generatedAt: new Date().toISOString(),
    base: args.base,
    runId: ctx.runId,
    globalConcurrency: args.concurrency,
    durationSeconds: args.duration,
    warmupSeconds: args.warmup,
    keyword: args.keyword,
    cycleId: args.cycleId,
    results,
  }
  if (args.out) {
    const target = path.resolve(args.out)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, `${JSON.stringify(output, null, 2)}\n`, 'utf8')
  }
  if (args.json) {
    console.log(JSON.stringify(output, null, 2))
  } else {
    console.log('')
    printTable(results)
    console.log('')
    for (const result of results) {
      if (result.skipped) {
        console.log(`⚠ ${result.key} 跳过：${result.reason}`)
        continue
      }
      const errors = Object.entries(result.errorDistribution || {})
      if (errors.length) console.log(`✗ ${result.key} 错误分布：${errors.map(([key, value]) => `${key}×${value}`).join(', ')}`)
    }
    console.log(`\nrunId=${ctx.runId}（建任务标题前缀 PERF-${ctx.runId}-，清理见 scripts/perf/cleanup.sql）`)
  }
}

main().catch((error) => {
  console.error(error.stack || error.message)
  process.exitCode = 1
})
