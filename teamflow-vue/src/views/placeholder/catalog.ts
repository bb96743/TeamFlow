/* ============================================================
   菜单骨架「规划中」页面目录
   ------------------------------------------------------------
   用途：V6 新增的 42 个菜单、以及已入库但前端页面尚未交付的 M02 OKR 四页，
        都由统一占位页渲染。本文件是「路由路径 → 模块信息」的唯一配置表，
        占位页按当前路由路径查出模块名、职责、里程碑与计划功能。
   口径：模块职责取自 docs/需求总计划.md §2.2（职责一句话）；
        里程碑取自 §5.1（M1 主链路闭环 / M2 数据可见 / M3 协同沉淀 / M4 AI 与开放）；
        计划功能取自各模块 RULE.md §一（模块职责）与 §五（接口与权限分组）。
   注意：本文件只描述规划，不含任何业务数据；新增页面必须先在
        docs/需求总计划.md §2.4 追加需求，再回到这里登记。
   ============================================================ */

export type Milestone = 'M1' | 'M2' | 'M3' | 'M4'

export interface PlannedPage {
  /** 所属模块，含编号（如 M03 任务与工作流） */
  module: string
  /** 页面名称，与菜单名称保持一致 */
  title: string
  /** 模块一句话职责 */
  duty: string
  /** 所属里程碑 */
  milestone: Milestone
  /** 计划功能清单 */
  features: string[]
  /** 补充说明：仅当本页进度与模块整体不同时填写 */
  note?: string
  /** 已入库菜单的组件路径（新菜单统一为 placeholder/ 前缀，无需填写） */
  menuComponent?: string
}

/** 里程碑全称（docs/需求总计划.md §5.1） */
export const MILESTONE_TEXT: Record<Milestone, string> = {
  M1: 'M1 主链路闭环',
  M2: 'M2 数据可见',
  M3: 'M3 协同沉淀',
  M4: 'M4 AI 与开放',
}

/** 模块登记：名称、职责一句话、所属里程碑 */
const MODULE_INFO: Record<string, { name: string; duty: string; milestone: Milestone }> = {
  M02: { name: 'M02 目标与 OKR', duty: '目标周期、OKR 拆解、进度回写、对齐度检查。', milestone: 'M1' },
  M03: { name: 'M03 任务与工作流', duty: '任务派发跟踪与流程状态机编排。', milestone: 'M1' },
  M04: { name: 'M04 团队与成员', duty: '团队与项目组的成员关系与角色分工。', milestone: 'M1' },
  M05: { name: 'M05 绩效', duty: '绩效周期、指标、评分与结果归档，取数来自目标与任务数据。', milestone: 'M1' },
  M06: { name: 'M06 日程与会议', duty: '个人与团队日程、会议室预定、会议纪要与模板。', milestone: 'M3' },
  M07: { name: 'M07 知识与文档', duty: '知识空间、文档中心、全文检索，以及 SOP 与决策记录。', milestone: 'M3' },
  M08: { name: 'M08 招聘', duty: '职位、候选人、面试、Offer 与简历筛查。', milestone: 'M2' },
  M09: { name: 'M09 协作与消息', duty: '站内信、@提及、动态流、公告与外部触达。', milestone: 'M3' },
  M10: { name: 'M10 个人效率', duty: '待办收件箱、工时与专注、复盘与效能画像。', milestone: 'M1' },
  M11: { name: 'M11 AI 助手', duty: '对话入口、工具路由、知识库问答与日报生成。', milestone: 'M4' },
  M12: { name: 'M12 集成与开放', duty: '第三方集成、MCP Server、开放 API 与 Webhook。', milestone: 'M4' },
  M13: { name: 'M13 统计与洞察', duty: '跨域指标看板、任务统计与团队作战室。', milestone: 'M2' },
  M14: { name: 'M14 项目管理', duty: '项目立项、里程碑、甘特视图、风险登记与项目复盘。', milestone: 'M2' },
}

function planned(moduleKey: string, title: string, features: string[], extra: Partial<PlannedPage> = {}): PlannedPage {
  const info = MODULE_INFO[moduleKey]
  return {
    module: info.name,
    duty: info.duty,
    milestone: info.milestone,
    title,
    features,
    ...extra,
  }
}

/**
 * 路由路径 → 规划信息。
 * 键必须与 sys_menu.path 完全一致（V6__menu_skeleton.sql 与 V4__okr_menu.sql）。
 */
export const PLACEHOLDER_CATALOG: Record<string, PlannedPage> = {
  /* ================= M03 任务与工作流（里程碑 M1） ================= */
  '/task/list': planned('M03', '任务列表', [
    '任务全生命周期：创建、派发、认领、完成',
    '子任务、优先级、截止时间与参与人、关注人',
    '任务与目标关键结果的关联',
    '任务状态流转与流转记录',
  ]),
  '/task/board': planned('M03', '任务看板', [
    '按状态分列查看任务分布',
    '看板内直接切换任务状态',
    '按负责人、优先级、截止时间筛选看板内容',
  ]),
  '/task/flow-def': planned('M03', '流程定义', [
    '流程定义的新增与编辑',
    '节点与流转关系配置',
    '流程定义供流程实例引用',
  ]),
  '/task/approvals': planned('M03', '审批中心', [
    '流程实例与待审批事项列表',
    '审批意见填写与节点流转处理',
    '审批结果回写任务状态',
  ]),

  /* ================= M04 团队与成员（里程碑 M1） ================= */
  '/team/list': planned('M04', '团队管理', [
    '团队与项目组的创建、解散',
    '归属部门与团队负责人',
    '成员加入、移出与团队内角色（负责人 / 成员 / 观察者）',
  ]),
  '/team/workload': planned('M04', '成员负载', [
    '成员任务数与进行中数量概览',
    '负载数据由任务等模块提供，本页只读展示',
    '为其他模块提供按团队筛选与统计的维度',
  ]),

  /* ================= M05 绩效（里程碑 M1） ================= */
  '/perf/cycle': planned('M05', '绩效周期', [
    '周期定义与开启',
    '评分窗口与周期锁定',
    '周期归档与归集值预览',
  ]),
  '/perf/template': planned('M05', '指标模板', [
    '指标项定义与权重配置',
    '评分类别：可归集指标与主观评分',
    '评分标准维护',
  ]),
  '/perf/score': planned('M05', '绩效评分', [
    '本人自评与上级评分',
    '系统归集值展示（来自目标达成率与任务完成率）',
    '总分计算',
  ]),
  '/perf/result': planned('M05', '结果归档', [
    '绩效结果固化与锁定',
    '申诉记录与复核',
    '结果查询',
  ]),

  /* ================= M06 日程与会议（里程碑 M3） ================= */
  '/meeting/calendar': planned('M06', '日程日历', [
    '个人与团队日程',
    '时间块与提醒',
    '按日历视图查看安排',
  ]),
  '/meeting/room': planned('M06', '会议室', [
    '会议室资源档案维护',
    '可用性查询',
    '预定与冲突检测',
  ]),
  '/meeting/list': planned('M06', '会议管理', [
    '会议议程与参会人',
    '会议链接、取消与改期',
    '会议与日程、纪要的关联',
  ]),
  '/meeting/template': planned('M06', '纪要模板', [
    '常规会议、目标校准会、复盘会等专项模板',
    '会后纪要待办生成',
    '纪要正文归档到知识库',
  ]),

  /* ================= M07 知识与文档（里程碑 M3） ================= */
  '/knowledge/space': planned('M07', '知识空间', [
    '空间、目录、页面的分层结构',
    '逐层权限继承',
    '空间内页面树的浏览',
  ]),
  '/knowledge/page': planned('M07', '文档中心', [
    '页面编辑（富文本 / Markdown）',
    '版本历史与回滚',
    '附件管理与在线预览',
  ]),
  '/knowledge/search': planned('M07', '全文检索', [
    '跨文档、页面、任务的统一搜索入口',
    '搜索结果按类型归类',
    '检索结果直达正文位置',
  ]),

  /* ================= M08 招聘（里程碑 M2） ================= */
  '/recruit/job': planned('M08', '职位管理', [
    '职位描述（JD）维护',
    '招聘需求审批，复用任务与流程能力',
    '招聘负责人指派',
  ]),
  '/recruit/candidate': planned('M08', '候选人', [
    '简历入库与标签',
    '候选人查重与状态跟踪',
    '按职位描述匹配打分并给出命中原因',
  ]),
  '/recruit/interview': planned('M08', '面试安排', [
    '面试轮次编排',
    '面试官分配',
    '结构化评价表填写',
  ]),
  '/recruit/offer': planned('M08', 'Offer 管理', [
    'Offer 模板',
    'Offer 审批链',
    '入职后转为系统账号',
  ]),

  /* ================= M09 协作与消息（里程碑 M3） ================= */
  '/collab/message': planned('M09', '站内信', [
    '单聊与群组会话',
    '消息收发与已读状态',
    '在任务、文档中 @提及并收到通知',
  ]),
  '/collab/announcement': planned('M09', '公告', [
    '面向全员或部门的通知发布',
    '公告阅读状态',
    '重要公告置顶',
  ]),
  '/collab/feed': planned('M09', '动态流', [
    '团队活动流：谁完成了什么、谁改了什么',
    '按团队与时间筛选',
    '从动态跳转到对应业务对象',
  ]),

  /* ================= M10 个人效率（里程碑 M1） ================= */
  '/personal/todo': planned('M10', '待办收件箱', [
    '聚合所有「需要我行动」的事项',
    '统一处理入口：确认、转派、忽略',
    '各业务模块通过统一投递接口写入待办',
  ]),
  '/personal/time': planned('M10', '工时记录', [
    '任务级计时与工时填报',
    '专注计时（番茄钟与免打扰时段）',
    '工时数据供成本核算使用',
  ]),
  '/personal/review': planned('M10', '工作复盘', [
    '周复盘与经验沉淀',
    '复盘标签与检索',
    '复盘内容可归档到知识库',
  ]),
  '/personal/profile': planned('M10', '效能画像', [
    '任务吞吐、响应时长、承接量趋势',
    '仅本人可见',
    '只读展示，不提供修改入口',
  ]),

  /* ================= M11 AI 助手（里程碑 M4） ================= */
  '/ai/chat': planned('M11', 'AI 对话', [
    '统一自然语言交互入口',
    '意图识别与工具路由，把自然语言映射到具体业务能力',
    '会话历史管理',
  ]),
  '/ai/qa': planned('M11', '知识问答', [
    '基于知识库语料的语义检索',
    '回答带引用溯源',
    '引用可跳转到原文页面',
  ]),
  '/ai/report': planned('M11', '日报生成', [
    '从任务、目标、会议数据自动成稿',
    '生成结果可编辑后提交',
    '按人按周期生成周报',
  ]),

  /* ================= M12 集成与开放（里程碑 M4） ================= */
  '/integration/app': planned('M12', '第三方集成', [
    '钉钉、企业微信、飞书、GitHub、Jira 等接入',
    '统一授权管理',
    'Webhook 事件外发与失败重试',
  ]),
  '/integration/key': planned('M12', '开放密钥', [
    '开放 API 的 Key 申请、查看与吊销',
    '按 scope 授权可访问的接口范围',
    'MCP Server 复用同一套 Key',
  ]),

  /* ================= M13 统计与洞察（里程碑 M2） ================= */
  '/insight/task': planned('M13', '任务总览', [
    '任务完成率、逾期率',
    '周期趋势与成员分布',
    '统计口径与业务表保持一致',
  ]),
  '/insight/load': planned('M13', '团队负载', [
    '团队承接量与响应时长',
    '负载均衡情况',
    '按团队、按周期切换查看',
  ]),
  '/insight/cross': planned('M13', '跨域看板', [
    '目标达成、绩效分布、招聘漏斗等跨域指标',
    '只读取其他模块结果，不重复落库',
    '作战室大屏用的聚合视图',
  ]),
  '/insight/metric': planned('M13', '指标口径', [
    '指标定义与计算口径',
    '数据来源与刷新频率登记',
    '口径变更留痕',
  ]),

  /* ================= M14 项目管理（立项在 M2，甘特/风险/复盘在 M3） ================= */
  '/project/list': planned('M14', '项目列表', [
    '项目立项与负责人、参与人',
    '项目周期与状态流转（立项 → 进行中 → 结项 → 归档）',
    '项目关联目标与关键结果',
  ]),
  '/project/milestone': planned('M14', '里程碑', [
    '里程碑定义',
    '计划完成时间与实际完成时间',
    '完成度汇总',
  ]),
  '/project/gantt': planned('M14', '甘特视图', [
    '甘特图与时间线',
    '任务依赖与关键路径（任务数据来自任务模块，只读）',
    '按项目范围切换视图',
  ], { milestone: 'M3' }),
  '/project/risk': planned('M14', '风险登记', [
    '风险条目、责任人与应对措施',
    '风险状态流转与留痕',
    '按项目汇总风险',
  ], { milestone: 'M3' }),
  '/project/retro': planned('M14', '项目复盘', [
    '结项复盘模板',
    '结项报告生成',
    '复盘正文归档到知识库',
  ], { milestone: 'M3' }),

  /* ================= M02 目标与 OKR（已入库菜单，前端阶段二交付） ================= */
  '/okr/period': planned('M02', '目标周期', [
    '目标周期的建立与状态维护',
    '周期内目标与关键结果的组织',
    '周期切换与历史周期查看',
  ], {
    menuComponent: 'okr/period/index',
    note: '本模块的后端接口与权限码已在 M02 阶段一交付，前端页面属阶段二，尚未开发。',
  }),
  '/okr/objective': planned('M02', '目标制定', [
    '目标的建立与拆解',
    '目标对齐上级或部门目标',
    '目标进度与关键结果汇总',
  ], {
    menuComponent: 'okr/objective/index',
    note: '本模块的后端接口与权限码已在 M02 阶段一交付，前端页面属阶段二，尚未开发。',
  }),
  '/okr/key-result': planned('M02', '关键结果', [
    '关键结果的维护与权重',
    '进度更新与任务回写来源',
    '达成率计算',
  ], {
    menuComponent: 'okr/key-result/index',
    note: '本模块的后端接口与权限码已在 M02 阶段一交付，前端页面属阶段二，尚未开发。',
  }),
  '/okr/align-check': planned('M02', '对齐检查', [
    '目标对齐度检查',
    '未对齐目标的提示',
    '对齐关系可视化',
  ], {
    menuComponent: 'okr/align/index',
    note: '本模块的后端接口与权限码已在 M02 阶段一交付，前端页面属阶段二，尚未开发。',
  }),
}

/** 按当前路由路径取规划信息；未登记时返回 undefined（占位页使用通用文案兜底） */
export function placeholderPageOf(path: string): PlannedPage | undefined {
  return PLACEHOLDER_CATALOG[path]
}

/** 已入库菜单的组件路径 → 规划信息（供菜单解析：这些菜单本期也走占位页） */
export const PLANNED_BY_MENU_COMPONENT: Record<string, PlannedPage> = Object.entries(PLACEHOLDER_CATALOG)
  .reduce((acc, [, page]) => {
    if (page.menuComponent) acc[page.menuComponent] = page
    return acc
  }, {} as Record<string, PlannedPage>)
