<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionLabel from '@/components/ui/SectionLabel.vue'
import SpecPlate from '@/components/ui/SpecPlate.vue'
import Tag from '@/components/ui/Tag.vue'
import { MILESTONE_TEXT, placeholderPageOf, type PlannedPage } from '@/views/placeholder/catalog'

/* ============================================================
   菜单骨架统一占位页。
   菜单入口已建、业务功能尚未开发：本页只说明模块职责、所属里程碑与计划功能，
   不请求任何接口、不展示任何虚构数据，也不提供"看似可用"的操作按钮，
   避免把占位页误读成已完成功能。
   ============================================================ */
const route = useRoute()

const page = computed<PlannedPage>(() => {
  const found = placeholderPageOf(route.path)
  if (found) return found
  return {
    module: '待排期模块',
    title: String(route.meta.title ?? '规划中页面'),
    duty: '',
    milestone: 'M1',
    features: [],
  }
})

const milestoneText = computed(() => MILESTONE_TEXT[page.value.milestone] ?? page.value.milestone)
</script>

<template>
  <div class="page">
    <PageHeader :title="page.title" :desc="page.duty">
      <template #spec>
        <SpecPlate
          title="模块档案"
          :rows="[
            { label: '所属模块', value: page.module },
            { label: '所属里程碑', value: milestoneText },
            { label: '交付状态', value: '规划中' },
          ]"
        />
      </template>
      <Tag tone="info" dot>规划中</Tag>
    </PageHeader>

    <section class="band reveal">
      <div class="band-head">
        <SectionLabel title="当前状态" />
        <Tag tone="danger" dot>功能未实现</Tag>
      </div>
      <p style="max-width: 760px; font-size: 13px; line-height: 1.9; color: var(--ink-muted)">
        这是一张导航占位页：菜单入口已经开通，<b style="color: var(--ink)">{{ page.title }}</b>
        所在的<b style="color: var(--ink)">{{ page.module }}</b>仍在规划中，计划在
        <b style="color: var(--ink)">{{ milestoneText }}</b> 交付。本页没有真实数据，也不会调用任何业务接口。
      </p>
      <p v-if="page.note" style="max-width: 760px; margin-top: 8px; font-size: 13px; line-height: 1.9; color: var(--ink-muted)">
        {{ page.note }}
      </p>
    </section>

    <div class="bento-grid">
      <section class="bento bento-half">
        <div class="bento-head">
          <SectionLabel title="计划功能" />
          <span class="bento-meta">{{ page.features.length }} 项</span>
        </div>
        <ul v-if="page.features.length" style="margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 10px; font-size: 13px; line-height: 1.7; color: var(--ink)">
          <li v-for="item in page.features" :key="item">{{ item }}</li>
        </ul>
        <p v-else style="margin: 0; font-size: 13px; color: var(--ink-muted)">计划功能待需求确认后登记。</p>
      </section>

      <section class="bento bento-half">
        <div class="bento-head">
          <SectionLabel title="说明" />
        </div>
        <p style="margin: 0; font-size: 13px; line-height: 1.9; color: var(--ink-muted)">
          菜单齐全只代表导航骨架已建，不代表功能可用。模块是否完成以该模块的验收标准为准，
          完成前请勿按本页安排业务使用。
        </p>
        <p style="margin-top: 10px; font-size: 13px; line-height: 1.9; color: var(--ink-muted)">
          功能开发完成后，本占位页会由对应的业务页面替换；计划功能清单也会随需求评审结果更新。
        </p>
      </section>
    </div>
  </div>
</template>
