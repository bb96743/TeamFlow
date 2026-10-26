<script setup lang="ts">
import { Field, SectionLabel, Select, Tag, type Column } from '@/components/ui'
import { ListPage, UserSelect } from '@/components/business'
import { collabApi, type CollabFeedRow } from '@/api/collab'
import { useListPage } from '@/composables/list'
import { dateTimeText, FEED_ACTION_OPTIONS, FEED_BIZ_TEXT } from './support'

/* ============================================================
   动态流：只读的协作时间线。
   条目全部由业务模块的领域事件投影生成（例如任务完成、任务状态变更），
   本页不提供任何发布 / 编辑入口；需要沟通请走站内信。
   ============================================================ */

const list = useListPage<CollabFeedRow, { actorId: number | null; action: string }>({
  query: { actorId: null, action: '' },
  fetch: params => collabApi.feeds.list(params),
})

const columns: Column[] = [
  { key: 'occurredAt', title: '发生时间', width: 160 },
  { key: 'actorName', title: '成员', width: 110 },
  { key: 'actionLabel', title: '动作', width: 120 },
  { key: 'summary', title: '动态内容', minWidth: 280 },
  { key: 'biz', title: '关联对象', width: 140 },
]

function pickActor(value: string | number | null | undefined) {
  list.query.actorId = value === null || value === undefined || value === '' ? null : Number(value)
}

function bizText(row: CollabFeedRow): string {
  if (!row.bizType) return '—'
  const label = FEED_BIZ_TEXT[row.bizType] || row.bizType
  return row.bizId ? `${label} #${row.bizId}` : label
}
</script>

<template>
  <ListPage
    title="动态流"
    desc="团队的协作时间线：谁在什么时候完成了什么、改了什么。内容由业务事件自动生成，只读、不可编辑。"
    table-title="动态列表"
    :list="list"
    :columns="columns"
  >
    <template #before>
      <section class="band reveal">
        <div class="band-head">
          <SectionLabel title="关于动态流" />
        </div>
        <p class="faint" style="font-size: 12px; line-height: 1.8">
          动态由任务等业务动作自动投影生成，因此<b style="color: var(--ink)">没有发布入口</b>，也不能修改或删除；
          需要与同事沟通时请使用「站内信」，需要自己跟进的事项请到「个人效率 · 待办收件箱」。
        </p>
      </section>
    </template>

    <template #search>
      <Field label="成员">
        <UserSelect :model-value="list.query.actorId" placeholder="全部成员" @update:model-value="pickActor" />
      </Field>
      <Field label="动作">
        <Select v-model="list.query.action" :options="[{ label: '全部动作', value: '' }, ...FEED_ACTION_OPTIONS]" />
      </Field>
    </template>

    <template #cell-occurredAt="{ row }">
      <span class="tnum soft">{{ dateTimeText(row.occurredAt) }}</span>
    </template>
    <template #cell-actorName="{ row }">
      <span class="cell-title">{{ row.actorName || '—' }}</span>
    </template>
    <template #cell-actionLabel="{ row }">
      <Tag :tone="row.action === 'task_completed' ? 'ok' : row.action === 'task_deleted' ? 'danger' : 'info'">
        {{ row.actionLabel || row.action || '—' }}
      </Tag>
    </template>
    <template #cell-summary="{ row }">
      <span class="soft">{{ row.summary || '—' }}</span>
    </template>
    <template #cell-biz="{ row }">
      <span class="code-chip">{{ bizText(row) }}</span>
    </template>
  </ListPage>
</template>
