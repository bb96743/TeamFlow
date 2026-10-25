<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Button,
  DataTable,
  Metric,
  PageHeader,
  Panel,
  SectionLabel,
  Select,
  Tag,
  type Column,
} from '@/components/ui'
import { teamApi, type TeamMemberRow, type TeamOption } from '@/api/team'
import { toastError } from '@/composables/toast'

/* ============================================================
   成员负载（M04 · 团队视图）
   ------------------------------------------------------------
   按团队查看成员的任务负载（任务总数 / 进行中数）。
   负载数据由任务模块的只读接口提供，本页只做展示，不自行统计，
   也不在这里修改任务；口径与任务模块保持一致。
   ============================================================ */

const teams = ref<TeamOption[]>([])
const teamId = ref<number | null>(null)
const members = ref<TeamMemberRow[]>([])
const loading = ref(false)
const optionsLoading = ref(true)

const ROLE_LABEL: Record<string, string> = { OWNER: '负责人', MEMBER: '成员', VIEWER: '观察者' }

const teamOptions = computed(() => teams.value.map(team => ({ label: team.teamName, value: team.id })))
const currentTeam = computed(() => teams.value.find(team => team.id === teamId.value))

const totalTask = computed(() => members.value.reduce((sum, row) => sum + Number(row.taskTotal ?? 0), 0))
const totalInProgress = computed(() => members.value.reduce((sum, row) => sum + Number(row.taskInProgress ?? 0), 0))

const columns: Column[] = [
  { key: 'nickname', title: '成员', minWidth: 200 },
  { key: 'teamRole', title: '团队角色', width: 110 },
  { key: 'deptName', title: '所属部门', width: 150 },
  { key: 'taskTotal', title: '任务总数', width: 110, align: 'right' },
  { key: 'taskInProgress', title: '进行中', width: 110, align: 'right' },
  { key: 'loadRatio', title: '进行中占比', width: 130, align: 'right' },
]

async function loadOptions() {
  optionsLoading.value = true
  try {
    teams.value = await teamApi.teams.options()
    if (!teamId.value && teams.value.length) teamId.value = teams.value[0].id
    if (teamId.value) await loadMembers()
  } catch (error) {
    toastError(error, '团队列表加载失败')
  } finally {
    optionsLoading.value = false
  }
}

async function loadMembers() {
  if (!teamId.value) {
    members.value = []
    return
  }
  loading.value = true
  try {
    // 负载页只看在册成员：已移出成员不再产生当前负载
    members.value = await teamApi.members.list(teamId.value, false)
  } catch (error) {
    toastError(error, '成员负载加载失败')
  } finally {
    loading.value = false
  }
}

function switchTeam() {
  loadMembers()
}

function loadRatio(row: TeamMemberRow) {
  const total = Number(row.taskTotal ?? 0)
  if (!total) return '—'
  return `${Math.round((Number(row.taskInProgress ?? 0) / total) * 1000) / 10}%`
}

onMounted(loadOptions)
</script>

<template>
  <div class="page">
    <PageHeader
      title="成员负载"
      desc="按团队查看成员当前的 任务总数与进行中任务数，用于分工与负载平衡。负载数据由任务模块只读提供，本页不自行统计，也不在这里修改任务。"
    >
      <Select
        :model-value="teamId"
        :options="teamOptions"
        :placeholder="optionsLoading ? '团队加载中…' : '请选择团队'"
        filterable
        @update:model-value="teamId = $event === null || $event === undefined || $event === '' ? null : Number($event); switchTeam()"
      />
      <Button variant="secondary" icon="refresh" :loading="loading" @click="loadMembers">刷新</Button>
    </PageHeader>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="负载概览" />
        <Tag tone="info">数据来自任务是只读接口</Tag>
      </div>
      <div class="kv-grid">
        <div class="kv">
          <div class="kv-label">在册成员</div>
          <Metric :value="members.length" unit="人" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">合计任务</div>
          <Metric :value="totalTask" unit="项" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">进行中任务</div>
          <Metric :value="totalInProgress" unit="项" size="l" />
        </div>
        <div class="kv">
          <div class="kv-label">当前团队</div>
          <div class="kv-value">{{ currentTeam?.teamName || '未选择团队' }}</div>
        </div>
      </div>
    </section>

    <Panel
      :title="currentTeam ? `${currentTeam.teamName} · 成员负载` : '成员负载'"
      :meta="`共 ${members.length} 位在册成员`"
    >
      <DataTable :columns="columns" :data="members" row-key="id" :loading="loading" :min-width="720">
        <template #cell-nickname="{ row }">
          <div>
            <div class="cell-title">{{ row.nickname || row.username || `用户 #${row.userId}` }}</div>
            <div class="cell-sub">
              {{ row.username || '' }}<span v-if="row.joinTime"> · 加入于 {{ row.joinTime }}</span>
            </div>
          </div>
        </template>
        <template #cell-teamRole="{ row }">
          <Tag :tone="row.teamRole === 'OWNER' ? 'info' : 'neutral'">
            {{ row.teamRoleLabel || ROLE_LABEL[row.teamRole] || row.teamRole }}
          </Tag>
        </template>
        <template #cell-deptName="{ row }"><span class="muted">{{ row.deptName || '—' }}</span></template>
        <template #cell-taskTotal="{ row }"><span class="tnum">{{ row.taskTotal ?? 0 }}</span></template>
        <template #cell-taskInProgress="{ row }"><span class="tnum">{{ row.taskInProgress ?? 0 }}</span></template>
        <template #cell-loadRatio="{ row }"><span class="tnum soft">{{ loadRatio(row) }}</span></template>
      </DataTable>

      <p class="faint">
        团队角色（负责人 / 成员 / 观察者）仅表示团队内部分工，不是系统角色，也不会影响系统权限；负载只统计本人在任务模块中的顶层任务。
      </p>
    </Panel>
  </div>
</template>
