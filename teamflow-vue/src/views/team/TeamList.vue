<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  DataTable,
  Drawer,
  Field,
  Segmented,
  Select,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { DeptSelect, FormDialog, ListPage, PermissionButton, UserSelect } from '@/components/business'
import { teamApi, type TeamMemberRow, type TeamRole, type TeamRow, type TeamWriteRequest } from '@/api/team'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import { required, validate } from '@/utils/validate'

/* ============================================================
   团队管理（M04 · 团队 CRUD + 成员关系）
   ------------------------------------------------------------
   团队用于组织分工与跨模块筛选维度。团队角色（负责人 / 成员 / 观察者）
   只在团队内部起作用：它不是系统角色，也不会带来或取消任何系统权限；
   能不能查看/修改仍由系统权限决定。
   成员移出为「保留历史」：写移出时间而不删除记录，可用筛选查看已移出成员。
   ============================================================ */

const STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '正常', value: 'active' },
  { label: '已归档', value: 'archived' },
]
const EDIT_STATUS_OPTIONS = [
  { label: '正常', value: 'active' },
  { label: '已归档', value: 'archived' },
]
const MINE_OPTIONS = [
  { label: '全部团队', value: '' },
  { label: '我参与的团队', value: 'true' },
]
const ROLE_OPTIONS: Array<{ label: string; value: TeamRole }> = [
  { label: '负责人（仅团队内分工）', value: 'OWNER' },
  { label: '成员', value: 'MEMBER' },
  { label: '观察者', value: 'VIEWER' },
]
const ROLE_LABEL: Record<string, string> = { OWNER: '负责人', MEMBER: '成员', VIEWER: '观察者' }
const ROLE_TONE: Record<string, string> = { OWNER: 'info', MEMBER: 'neutral', VIEWER: 'neutral' }

const list = useListPage<TeamRow, { keyword: string; status: string; mine: string }>({
  query: { keyword: '', status: '', mine: '' },
  fetch: params => teamApi.teams.list(params),
})

const columns: Column[] = [
  { key: 'teamName', title: '团队名称', minWidth: 220 },
  { key: 'ownerName', title: '负责人', width: 130 },
  { key: 'memberCount', title: '在册成员', width: 110, align: 'right' },
  { key: 'deptName', title: '归属部门', width: 150 },
  { key: 'status', title: '状态', width: 100 },
  { key: 'createTime', title: '创建时间', width: 148 },
  { key: 'actions', title: '操作', width: 150, className: 'col-actions' },
]

/* ── 团队表单 ── */
interface TeamForm {
  id?: number
  teamName: string
  teamCode: string
  teamDesc: string
  deptId: number | null
  ownerId: number | null
  status: string
  sort: number | null
  remark: string
}
const emptyForm = (): TeamForm => ({
  teamName: '', teamCode: '', teamDesc: '', deptId: null, ownerId: null, status: 'active', sort: 10, remark: '',
})
const dialog = ref(false)
const saving = ref(false)
const form = reactive<TeamForm>(emptyForm())
const errors = ref<Partial<Record<keyof TeamForm, string>>>({})

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  errors.value = {}
  dialog.value = true
}

function openEdit(row: TeamRow) {
  Object.assign(form, {
    id: row.id,
    teamName: row.teamName,
    teamCode: row.teamCode ?? '',
    teamDesc: row.teamDesc ?? '',
    deptId: row.deptId ?? null,
    ownerId: row.ownerId ?? null,
    status: row.status,
    sort: row.sort ?? 10,
    remark: row.remark ?? '',
  })
  errors.value = {}
  dialog.value = true
}

async function submitTeam() {
  errors.value = validate(form, { teamName: [required('请填写团队名称')] })
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload: TeamWriteRequest = {
    teamName: form.teamName.trim(),
    teamCode: form.teamCode.trim() || null,
    teamDesc: form.teamDesc.trim() || null,
    deptId: form.deptId,
    status: form.status,
    sort: form.sort ?? 0,
    remark: form.remark.trim() || null,
  }
  try {
    if (form.id) {
      await teamApi.teams.update(form.id, payload)
      pushToast('团队已更新')
    } else {
      await teamApi.teams.create({ ...payload, ownerId: form.ownerId })
      pushToast('团队已创建，可继续添加成员')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '团队保存失败')
  } finally {
    saving.value = false
  }
}

async function dissolve(row: TeamRow) {
  const ok = await confirm({
    title: '解散团队',
    message: `确认解散团队「${row.teamName}」吗？解散为逻辑删除：团队不再出现在列表与筛选中，团队成员历史与团队数据仍会保留，便于日后追溯。`,
    confirmText: '解散',
    danger: true,
  })
  if (!ok) return
  try {
    await teamApi.teams.dissolve(row.id)
    pushToast('团队已解散，成员历史已保留')
    if (memberTeam.value?.id === row.id) closeMembers()
    await list.load()
  } catch (error) {
    // 「仅团队负责人可解散」等团队内校验由后端返回原文
    toastError(error, '团队解散失败')
  }
}

/* ── 成员抽屉 ── */
const memberTeam = ref<TeamRow | null>(null)
const drawerOpen = ref(false)
const memberTab = ref<'active' | 'left'>('active')
const members = ref<TeamMemberRow[]>([])
const membersLoading = ref(false)

const memberColumns: Column[] = [
  { key: 'nickname', title: '成员', minWidth: 180 },
  { key: 'teamRole', title: '团队角色', width: 110 },
  { key: 'joinTime', title: '加入时间', width: 140 },
  { key: 'leaveTime', title: '移出时间', width: 140 },
  { key: 'load', title: '任务负载', width: 120 },
  { key: 'actions', title: '操作', width: 120, className: 'col-actions' },
]

const shownMembers = computed(() => memberTab.value === 'active'
  ? members.value.filter(row => !row.leaveTime)
  : members.value.filter(row => Boolean(row.leaveTime)))

function openMembers(row: TeamRow) {
  memberTeam.value = row
  memberTab.value = 'active'
  members.value = []
  drawerOpen.value = true
  loadMembers()
}

function closeMembers() {
  drawerOpen.value = false
  memberTeam.value = null
  members.value = []
}

async function loadMembers() {
  if (!memberTeam.value) return
  membersLoading.value = true
  try {
    // includeLeft=true 时同时返回在册与已移出，页面用页签区分，历史行不会被删除
    members.value = await teamApi.members.list(memberTeam.value.id, true)
  } catch (error) {
    toastError(error, '成员列表加载失败')
  } finally {
    membersLoading.value = false
  }
}

async function refreshAfterMemberChange() {
  await loadMembers()
  await list.load()
  if (memberTeam.value) {
    const fresh = list.rows.value.find(row => row.id === memberTeam.value?.id)
    if (fresh) memberTeam.value = fresh
  }
}

/* 添加成员 */
const addDialog = ref(false)
const addSaving = ref(false)
const addForm = reactive<{ userId: number | null; teamRole: TeamRole; remark: string }>({
  userId: null, teamRole: 'MEMBER', remark: '',
})
const addError = ref('')

function openAddMember() {
  addForm.userId = null
  addForm.teamRole = 'MEMBER'
  addForm.remark = ''
  addError.value = ''
  addDialog.value = true
}

async function submitAddMember() {
  if (!memberTeam.value) return
  if (!addForm.userId) {
    addError.value = '请选择要加入团队的成员'
    return
  }
  addSaving.value = true
  try {
    await teamApi.members.add(memberTeam.value.id, {
      userId: addForm.userId,
      teamRole: addForm.teamRole,
      remark: addForm.remark.trim() || undefined,
    })
    pushToast('成员已加入团队')
    addDialog.value = false
    await refreshAfterMemberChange()
  } catch (error) {
    toastError(error, '添加成员失败')
  } finally {
    addSaving.value = false
  }
}

/* 变更角色 */
const roleDialog = ref(false)
const roleSaving = ref(false)
const roleRow = ref<TeamMemberRow | null>(null)
const roleValue = ref<TeamRole>('MEMBER')

function openRole(row: TeamMemberRow) {
  roleRow.value = row
  roleValue.value = row.teamRole
  roleDialog.value = true
}

async function submitRole() {
  if (!memberTeam.value || !roleRow.value) return
  roleSaving.value = true
  try {
    await teamApi.members.changeRole(memberTeam.value.id, roleRow.value.id, roleValue.value)
    pushToast('团队角色已更新（不影响系统权限）')
    roleDialog.value = false
    await refreshAfterMemberChange()
  } catch (error) {
    toastError(error, '变更角色失败')
  } finally {
    roleSaving.value = false
  }
}

/* 移出成员 */
async function leaveMember(row: TeamMemberRow) {
  if (!memberTeam.value) return
  const ok = await confirm({
    title: '移出成员',
    message: `确认将「${row.nickname || row.username || '该成员'}」移出团队吗？移出只记录移出时间，历史归属不会删除，可在「已移出」页签查看。`,
    confirmText: '移出',
    danger: true,
  })
  if (!ok) return
  try {
    await teamApi.members.leave(memberTeam.value.id, row.id)
    pushToast('成员已移出，历史记录已保留')
    await refreshAfterMemberChange()
  } catch (error) {
    toastError(error, '移出成员失败')
  }
}

/* 设置负责人 */
const ownerDialog = ref(false)
const ownerSaving = ref(false)
const ownerUserId = ref<number | null>(null)
const ownerError = ref('')

function openOwner() {
  ownerUserId.value = memberTeam.value?.ownerId ?? null
  ownerError.value = ''
  ownerDialog.value = true
}

async function submitOwner() {
  if (!memberTeam.value) return
  if (!ownerUserId.value) {
    ownerError.value = '请选择团队负责人'
    return
  }
  ownerSaving.value = true
  try {
    await teamApi.teams.setOwner(memberTeam.value.id, ownerUserId.value)
    pushToast('负责人已更新（仅团队内分工）')
    ownerDialog.value = false
    await refreshAfterMemberChange()
  } catch (error) {
    toastError(error, '设置负责人失败')
  } finally {
    ownerSaving.value = false
  }
}
</script>

<template>
  <ListPage
    title="团队管理"
    desc="团队用于组织分工与跨模块筛选。团队角色（负责人 / 成员 / 观察者）只在团队内部起作用，它不是系统角色，也不会带来或取消任何系统权限；能否查看或修改团队仍由系统权限决定。"
    table-title="团队列表"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="team:team:add" variant="primary" icon="plus" @click="openCreate">新增团队</PermissionButton>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="团队名称 / 编码" clearable @enter="list.search()" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="STATUS_OPTIONS" />
      </Field>
      <Field label="成员范围">
        <Select v-model="list.query.mine" :options="MINE_OPTIONS" />
      </Field>
    </template>

    <template #cell-teamName="{ row }">
      <div>
        <div class="cell-title">{{ row.teamName }}</div>
        <div class="cell-sub">
          <span v-if="row.teamCode" class="code-chip">{{ row.teamCode }}</span>
          <span v-else>未设置团队编码</span>
        </div>
      </div>
    </template>
    <template #cell-ownerName="{ row }">
      <span class="muted">{{ row.ownerName || '暂未指定' }}</span>
    </template>
    <template #cell-memberCount="{ row }"><span class="tnum">{{ row.memberCount ?? 0 }}</span></template>
    <template #cell-deptName="{ row }">
      <span class="muted">{{ row.deptName || '—' }}</span>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="row.status === 'active' ? 'ok' : 'neutral'" dot>{{ row.statusLabel || row.status }}</Tag>
    </template>
    <template #cell-createTime="{ row }"><span class="tnum soft">{{ row.createTime || '—' }}</span></template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton perm="team:member:list" icon-only icon="users" label="成员管理" @click="openMembers(row)" />
        <PermissionButton perm="team:team:edit" icon-only icon="edit" label="编辑团队" @click="openEdit(row)" />
        <PermissionButton perm="team:team:remove" icon-only icon="trash" label="解散团队" danger @click="dissolve(row)" />
      </div>
    </template>

    <!-- 成员管理：在册 / 已移出 分开查看；移出不删历史 -->
    <Drawer
      :open="drawerOpen"
      origin-from="tr"
      :title="memberTeam ? `成员管理 · ${memberTeam.teamName}` : '成员管理'"
      sub="团队角色只用于团队内部分工（例如仅负责人可解散团队），与系统角色和权限无关。"
      :width="900"
      @close="closeMembers"
    >
      <div>
        <div class="band-head">
          <Segmented
            :model-value="memberTab"
            label="成员范围"
            :options="[{ label: '在册成员', value: 'active' }, { label: '已移出', value: 'left' }]"
            @update:model-value="memberTab = $event as 'active' | 'left'"
          />
          <div class="band-tools">
            <PermissionButton perm="team:team:edit" variant="secondary" icon="user" @click="openOwner">设置负责人</PermissionButton>
            <PermissionButton perm="team:member:add" variant="primary" icon="plus" @click="openAddMember">添加成员</PermissionButton>
          </div>
        </div>

        <p class="cell-sub" style="margin: 0 0 14px">
          在册成员为当前仍在团队中的人；「已移出」是历史记录（移出只写移出时间，不删除归属数据），可用于追溯过往分工。
        </p>

        <DataTable :columns="memberColumns" :data="shownMembers" row-key="id" :loading="membersLoading" :min-width="760">
          <template #cell-nickname="{ row }">
            <div>
              <div class="cell-title">{{ row.nickname || row.username || `用户 #${row.userId}` }}</div>
              <div class="cell-sub">
                {{ row.username || '' }}<span v-if="row.deptName"> · {{ row.deptName }}</span>
              </div>
            </div>
          </template>
          <template #cell-teamRole="{ row }">
            <Tag :tone="ROLE_TONE[row.teamRole] ?? 'neutral'">{{ row.teamRoleLabel || ROLE_LABEL[row.teamRole] }}</Tag>
          </template>
          <template #cell-joinTime="{ row }"><span class="tnum soft">{{ row.joinTime || '—' }}</span></template>
          <template #cell-leaveTime="{ row }"><span class="tnum soft">{{ row.leaveTime || '在册' }}</span></template>
          <template #cell-load="{ row }">
            <span class="tnum">{{ row.taskInProgress ?? 0 }}</span>
            <span class="cell-sub">进行中 / 共 {{ row.taskTotal ?? 0 }}</span>
          </template>
          <template #cell-actions="{ row }">
            <div v-if="!row.leaveTime" class="row-actions">
              <PermissionButton
                perm="team:member:role"
                icon-only
                icon="role"
                label="变更团队角色"
                @click="openRole(row)"
              />
              <PermissionButton
                perm="team:member:remove"
                icon-only
                icon="ban"
                label="移出成员"
                danger
                @click="leaveMember(row)"
              />
            </div>
            <span v-else class="cell-sub">历史记录</span>
          </template>
        </DataTable>

        <p class="faint" style="margin: 14px 0 0">
          任务负载由任务是只读接口提供（任务总数 / 进行中数），本页不自行统计；数据会随任务状态实时变化。
        </p>
      </div>
    </Drawer>

    <!-- 团队新增 / 编辑 -->
    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑团队' : '新增团队'"
      sub="团队负责人同时会成为团队内的负责人角色；这只影响团队内分工，不改变系统角色。"
      :saving="saving"
      @close="dialog = false"
      @submit="submitTeam"
    >
      <Field label="团队名称" required :error="errors.teamName" wide>
        <TextInput v-model="form.teamName" placeholder="如：增长项目组" :invalid="!!errors.teamName" :maxlength="100" />
      </Field>
      <Field label="团队编码" hint="可空，唯一；便于其他模块引用">
        <TextInput v-model="form.teamCode" placeholder="如：growth-2026" :maxlength="64" />
      </Field>
      <Field label="归属部门" hint="引用组织架构，不复制部门信息">
        <DeptSelect v-model="form.deptId" clearable />
      </Field>
      <Field v-if="!form.id" label="负责人" hint="可稍后通过成员管理设置">
        <UserSelect v-model="form.ownerId" placeholder="请选择负责人" />
      </Field>
      <Field label="状态">
        <Select v-model="form.status" :options="EDIT_STATUS_OPTIONS" />
      </Field>
      <Field label="显示排序">
        <TextInput v-model="form.sort" type="number" />
      </Field>
      <Field label="团队说明" span>
        <TextInput v-model="form.teamDesc" multiline :rows="2" placeholder="这个团队负责什么、边界在哪里" :maxlength="500" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="form.remark" multiline :rows="2" placeholder="其他需要说明的信息" :maxlength="200" />
      </Field>
    </FormDialog>

    <!-- 添加成员 -->
    <FormDialog
      :open="addDialog"
      title="添加成员"
      sub="成员来自系统用户；加入会自动记录加入时间。"
      :saving="addSaving"
      @close="addDialog = false"
      @submit="submitAddMember"
    >
      <Field label="选择用户" required :error="addError" wide>
        <UserSelect v-model="addForm.userId" placeholder="请选择要加入的用户" />
      </Field>
      <Field label="团队角色" group hint="仅团队内分工，不是系统权限">
        <Select v-model="addForm.teamRole" :options="ROLE_OPTIONS" />
      </Field>
      <Field label="备注" span>
        <TextInput v-model="addForm.remark" placeholder="如：负责需求分析" :maxlength="200" />
      </Field>
    </FormDialog>

    <!-- 变更团队角色 -->
    <FormDialog
      :open="roleDialog"
      title="变更团队角色"
      sub="团队角色只决定团队内部分工（如负责人可解散团队），不会改变该用户的系统角色或权限。"
      :saving="roleSaving"
      submit-text="保存角色"
      @close="roleDialog = false"
      @submit="submitRole"
    >
      <Field label="成员" wide>
        <TextInput :model-value="roleRow?.nickname || roleRow?.username || ''" readonly />
      </Field>
      <Field label="团队角色" group hint="设为负责人会移交负责人，原负责人自动变为成员">
        <Select v-model="roleValue" :options="ROLE_OPTIONS" />
      </Field>
    </FormDialog>

    <!-- 设置负责人 -->
    <FormDialog
      :open="ownerDialog"
      title="设置负责人"
      sub="负责人是团队内分工；不会授予或取消任何系统权限。"
      :saving="ownerSaving"
      submit-text="保存负责人"
      @close="ownerDialog = false"
      @submit="submitOwner"
    >
      <Field label="负责人" required :error="ownerError" wide>
        <UserSelect v-model="ownerUserId" placeholder="请选择负责人" />
      </Field>
    </FormDialog>
  </ListPage>
</template>
