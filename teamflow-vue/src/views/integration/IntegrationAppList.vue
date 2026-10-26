<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Button,
  Field,
  Modal,
  Select,
  Tag,
  TextInput,
  type Column,
} from '@/components/ui'
import { FormDialog, ListPage, PermissionButton } from '@/components/business'
import { useListPage } from '@/composables/list'
import { confirm } from '@/composables/confirm'
import { pushToast, toastError } from '@/composables/toast'
import {
  integrationApi,
  type IntegrationAppCheckResult,
  type IntegrationAppRow,
  type IntegrationAppWriteRequest,
  type IntegrationEncryptionStatus,
} from '@/api/integration'
import { required, validate } from '@/utils/validate'

/* ============================================================
   集成中心（M12 · 2.4.1）
   ------------------------------------------------------------
   第三方接入配置 + 凭据加密存储 + 连通性检测。
   约束（与后端一致）：
     · 凭据保存后只保留末 4 位提示位，界面不回显明文；编辑留空=不修改；
     · 检测只对「接入地址」做可达性探测，失败原因原样展示，绝不显示成"已连接"；
     · **已配置 ≠ 已验证联通**：真实第三方业务调用需在具备凭据与网络的环境验证。
   ============================================================ */

const APP_TYPE_OPTIONS = [
  { label: '钉钉', value: 'dingtalk' },
  { label: '企业微信', value: 'wecom' },
  { label: '飞书', value: 'feishu' },
  { label: 'GitHub', value: 'github' },
  { label: 'Jira', value: 'jira' },
  { label: '开放调用方', value: 'open_client' },
]
const APP_TYPE_LABEL: Record<string, string> = {
  dingtalk: '钉钉', wecom: '企业微信', feishu: '飞书', github: 'GitHub', jira: 'Jira', open_client: '开放调用方',
}
const STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '启用', value: 'enabled' },
  { label: '停用', value: 'disabled' },
]

const list = useListPage<IntegrationAppRow, { keyword: string; appType: string; status: string }>({
  query: { keyword: '', appType: '', status: '' },
  fetch: params => integrationApi.apps.list(params),
})

const encryption = ref<IntegrationEncryptionStatus | null>(null)
const encryptionReady = computed(() => encryption.value?.credentialEncryptionConfigured !== false)

onMounted(async () => {
  try {
    encryption.value = await integrationApi.apps.encryptionStatus()
  } catch {
    encryption.value = null
  }
})

const columns: Column[] = [
  { key: 'appName', title: '接入应用', minWidth: 240 },
  { key: 'endpointUrl', title: '接入地址', minWidth: 220 },
  { key: 'credential', title: '凭据', width: 150 },
  { key: 'status', title: '状态', width: 96 },
  { key: 'lastCheck', title: '最近连通性检测', minWidth: 240 },
  { key: 'actions', title: '操作', width: 150, className: 'col-actions' },
]

/* ── 新增 / 编辑 ── */
interface AppForm {
  id?: number
  appCode: string
  appName: string
  appType: string
  endpointUrl: string
  configJson: string
  credential: string
  status: string
  remark: string
}
const emptyForm = (): AppForm => ({
  appCode: '',
  appName: '',
  appType: 'open_client',
  endpointUrl: '',
  configJson: '',
  credential: '',
  status: 'enabled',
  remark: '',
})
const dialog = ref(false)
const saving = ref(false)
const form = reactive<AppForm>(emptyForm())
const errors = ref<Partial<Record<keyof AppForm, string>>>({})
const currentCredentialHint = ref<string | null>(null)
const currentCredentialConfigured = ref(false)

function openCreate() {
  Object.assign(form, emptyForm(), { id: undefined })
  currentCredentialHint.value = null
  currentCredentialConfigured.value = false
  errors.value = {}
  dialog.value = true
}

function openEdit(row: IntegrationAppRow) {
  Object.assign(form, {
    id: row.id,
    appCode: row.appCode,
    appName: row.appName,
    appType: row.appType,
    endpointUrl: row.endpointUrl ?? '',
    configJson: row.configJson ?? '',
    credential: '',
    status: row.status,
    remark: row.remark ?? '',
  })
  currentCredentialHint.value = row.credentialHint ?? null
  currentCredentialConfigured.value = row.credentialConfigured === true
  errors.value = {}
  dialog.value = true
}

async function submitApp() {
  const nextErrors = validate(form, {
    appCode: [required('请填写应用编码')],
    appName: [required('请填写应用名称')],
  })
  if (form.endpointUrl && !/^https?:\/\//i.test(form.endpointUrl.trim())) {
    nextErrors.endpointUrl = '接入地址必须以 http:// 或 https:// 开头'
  }
  errors.value = nextErrors
  if (Object.keys(errors.value).length) return
  saving.value = true
  const payload: IntegrationAppWriteRequest = {
    appCode: form.appCode.trim(),
    appName: form.appName.trim(),
    appType: form.appType,
    endpointUrl: form.endpointUrl.trim() || null,
    configJson: form.configJson.trim() || null,
    credential: form.credential.trim() || null,
    status: form.status,
    remark: form.remark.trim() || null,
  }
  try {
    if (form.id) {
      await integrationApi.apps.update(form.id, payload)
      pushToast('接入应用已保存（凭据加密存储，不回显明文）')
    } else {
      await integrationApi.apps.create(payload)
      pushToast('接入应用已创建；请先做连通性检测确认接入地址可用')
    }
    dialog.value = false
    await list.load()
  } catch (error) {
    toastError(error, '接入应用保存失败')
  } finally {
    saving.value = false
  }
}

async function removeApp(row: IntegrationAppRow) {
  const ok = await confirm({
    title: '删除接入应用',
    message: `确认删除「${row.appName}」吗？删除后该应用的凭据与检测记录一并移除，相关集成调用将失效。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await integrationApi.apps.remove(row.id)
    pushToast('接入应用已删除')
    await list.load()
  } catch (error) {
    toastError(error, '接入应用删除失败')
  }
}

/* ── 连通性检测（失败原因原样展示） ── */
const checkOpen = ref(false)
const checkRow = ref<IntegrationAppRow | null>(null)
const checkResult = ref<IntegrationAppCheckResult | null>(null)
const checkError = ref('')
const checking = ref(false)

async function runCheck(row: IntegrationAppRow) {
  checkRow.value = row
  checkResult.value = null
  checkError.value = ''
  checking.value = true
  checkOpen.value = true
  try {
    checkResult.value = await integrationApi.apps.check(row.id)
    await list.load()
  } catch (error) {
    checkError.value = error instanceof Error ? error.message : '连通性检测请求失败'
  } finally {
    checking.value = false
  }
}

function checkTone(row: IntegrationAppRow) {
  if (row.lastCheckResult === 'success') return 'ok'
  if (row.lastCheckResult === 'failed') return 'danger'
  return 'neutral'
}
function checkLabel(row: IntegrationAppRow) {
  if (row.lastCheckResult === 'success') return '地址可达'
  if (row.lastCheckResult === 'failed') return '检测失败'
  return '未检测'
}
</script>

<template>
  <ListPage
    title="集成中心"
    desc="第三方接入配置与凭据管理。凭据保存时加密存储，界面只显示末 4 位提示位；连通性检测只验证接入地址是否可访问，失败原因原样展示——已配置不等于已验证联通。"
    table-title="接入应用"
    :list="list"
    :columns="columns"
  >
    <template #actions>
      <PermissionButton perm="integration:app:add" variant="primary" icon="plus" @click="openCreate">
        新增接入应用
      </PermissionButton>
    </template>

    <template #before>
      <p class="faint" style="margin: 0 0 12px">
        凭据只保存加密后的值、不保存明文，编辑时留空表示不修改；连通性检测只对「接入地址」做一次可达性探测，
        <b>真实第三方业务调用（钉钉/企微/飞书/GitHub/Jira）需在具备凭据与网络的环境另行验证</b>。
        <span v-if="encryption && !encryptionReady" class="danger-text">
          当前服务未配置凭据加密密钥，保存凭据会被拒绝（不会以明文存储）。
        </span>
      </p>
    </template>

    <template #search>
      <Field label="关键词" wide>
        <TextInput v-model="list.query.keyword" icon="search" placeholder="应用名称 / 编码" clearable @enter="list.search()" />
      </Field>
      <Field label="接入类型">
        <Select v-model="list.query.appType" :options="[{ label: '全部类型', value: '' }, ...APP_TYPE_OPTIONS]" />
      </Field>
      <Field label="状态">
        <Select v-model="list.query.status" :options="STATUS_OPTIONS" />
      </Field>
    </template>

    <template #cell-appName="{ row }">
      <div>
        <div class="cell-title">{{ row.appName }}</div>
        <div class="cell-sub">
          <span class="code-chip">{{ row.appCode }}</span>
          <span class="muted"> · {{ row.appTypeLabel || APP_TYPE_LABEL[row.appType] || row.appType }}</span>
        </div>
      </div>
    </template>
    <template #cell-endpointUrl="{ row }">
      <span class="cell-sub">{{ row.endpointUrl || '未配置接入地址' }}</span>
    </template>
    <template #cell-credential="{ row }">
      <div>
        <Tag v-if="row.credentialConfigured" tone="ok" dot>已配置凭据</Tag>
        <Tag v-else tone="neutral">未配置凭据</Tag>
        <div v-if="row.credentialConfigured && row.credentialHint" class="cell-sub tnum">{{ row.credentialHint }}</div>
      </div>
    </template>
    <template #cell-status="{ row }">
      <Tag :tone="row.status === 'enabled' ? 'ok' : 'neutral'">{{ row.statusLabel || row.status }}</Tag>
    </template>
    <template #cell-lastCheck="{ row }">
      <div>
        <Tag :tone="checkTone(row)" dot>{{ checkLabel(row) }}</Tag>
        <div v-if="row.lastCheckTime" class="cell-sub tnum">{{ row.lastCheckTime }}</div>
        <div v-if="row.lastCheckMessage" class="cell-sub">{{ row.lastCheckMessage }}</div>
      </div>
    </template>
    <template #cell-actions="{ row }">
      <div class="row-actions">
        <PermissionButton
          perm="integration:app:check"
          icon-only
          icon="activity"
          label="连通性检测"
          @click="runCheck(row)"
        />
        <PermissionButton
          perm="integration:app:edit"
          icon-only
          icon="edit"
          label="编辑接入应用"
          @click="openEdit(row)"
        />
        <PermissionButton
          perm="integration:app:remove"
          icon-only
          icon="trash"
          label="删除接入应用"
          danger
          @click="removeApp(row)"
        />
      </div>
    </template>

    <!-- 连通性检测结果：成功也只说明地址可达；失败原因原样展示 -->
    <Modal
      :open="checkOpen"
      :title="checkRow ? `连通性检测 · ${checkRow.appName}` : '连通性检测'"
      sub="仅验证接入地址是否可访问，不代表第三方业务接口与凭据有效"
      :width="620"
      @close="checkOpen = false"
    >
      <div v-if="checking" class="faint">正在检测接入地址…</div>
      <div v-else-if="checkError">
        <Tag tone="danger" dot>检测请求失败</Tag>
        <p class="check-message is-danger">{{ checkError }}</p>
      </div>
      <div v-else-if="checkResult">
        <div class="check-head">
          <Tag :tone="checkResult.success ? 'ok' : 'danger'" dot>
            {{ checkResult.success ? '地址可达' : '检测失败' }}
          </Tag>
          <span class="cell-sub tnum">{{ checkResult.checkedAt }}</span>
        </div>
        <p class="check-message" :class="{ 'is-danger': !checkResult.success }">{{ checkResult.message }}</p>
        <div class="kv-grid" style="margin-top: 12px">
          <div class="kv">
            <div class="kv-label">接入地址</div>
            <div class="kv-value">{{ checkResult.endpointUrl || '未配置' }}</div>
          </div>
          <div class="kv">
            <div class="kv-label">凭据</div>
            <div class="kv-value">{{ checkResult.credentialConfigured ? '已配置（加密存储）' : '未配置' }}</div>
          </div>
        </div>
        <p class="faint" style="margin-top: 10px">{{ checkResult.limitation || '真实第三方业务调用未验证' }}</p>
      </div>
      <template #footer>
        <Button variant="primary" @click="checkOpen = false">知道了</Button>
      </template>
    </Modal>

    <!-- 接入应用表单 -->
    <FormDialog
      :open="dialog"
      :title="form.id ? '编辑接入应用' : '新增接入应用'"
      sub="凭据加密存储、不回显明文；编辑时凭据留空表示不修改。接入地址用于连通性检测，请填写可访问的服务地址。"
      :width="720"
      :saving="saving"
      @close="dialog = false"
      @submit="submitApp"
    >
      <Field label="应用编码" required :error="errors.appCode" hint="创建后不可修改，用于识别接入方">
        <TextInput v-model="form.appCode" :readonly="!!form.id" placeholder="如：github-prod" :invalid="!!errors.appCode" :maxlength="64" />
      </Field>
      <Field label="应用名称" required :error="errors.appName">
        <TextInput v-model="form.appName" placeholder="如：研发 GitHub 集成" :invalid="!!errors.appName" :maxlength="100" />
      </Field>
      <Field label="接入类型">
        <Select v-model="form.appType" :options="APP_TYPE_OPTIONS" />
      </Field>
      <Field label="状态">
        <Select v-model="form.status" :options="[{ label: '启用', value: 'enabled' }, { label: '停用', value: 'disabled' }]" />
      </Field>
      <Field label="接入地址" :error="errors.endpointUrl" hint="http(s) 地址；连通性检测会访问它">
        <TextInput v-model="form.endpointUrl" placeholder="如：https://api.github.com" :invalid="!!errors.endpointUrl" :maxlength="500" />
      </Field>
      <Field label="非敏感配置" hint="JSON；请勿把密钥/令牌写在这里">
        <TextInput v-model="form.configJson" placeholder='如：{"owner":"teamflow"}' :maxlength="2000" />
      </Field>
      <Field
        label="凭据"
        span
        :hint="currentCredentialConfigured
          ? `已配置凭据（${currentCredentialHint || '末 4 位'}）；留空表示不修改`
          : '保存后只保留末 4 位提示位，界面与日志不回显明文'"
      >
        <TextInput
          v-model="form.credential"
          type="password"
          autocomplete="new-password"
          :placeholder="currentCredentialConfigured ? '留空表示不修改凭据' : '输入第三方凭据（如访问令牌）'"
          :maxlength="2000"
        />
      </Field>
      <p v-if="!encryptionReady" class="danger-text" style="grid-column: 1 / -1; margin: 0">
        服务端未配置凭据加密密钥：此时保存凭据会被拒绝（不会以明文存储）。请先配置环境变量后重试。
      </p>
      <Field label="备注" span>
        <TextInput v-model="form.remark" placeholder="用途、负责人等说明" :maxlength="500" />
      </Field>
    </FormDialog>
  </ListPage>
</template>

<style scoped>
.check-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.check-message {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--blue-soft);
  color: var(--ink);
  line-height: 1.6;
  word-break: break-word;
}
.check-message.is-danger { background: var(--red-soft); color: var(--red-text); }
.danger-text { color: var(--red-text); }
</style>
