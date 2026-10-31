<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { accessRequestApi } from '@/api/access-request'
import { useAuthStore } from '@/stores/auth'
import { usePageRefresh } from '@/composables/usePageRefresh'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type {
  AccessCategory,
  AccessGrant,
  AccessLevel,
  AccessRequest,
  AccessRequestStatus,
} from '@/types/access-request'
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'

/**
 * 敏感访问申请。
 *
 * 页面同时承担三种视角：我的申请、待审批（有 approve 权限）、我的/全部 grant。
 * P2 的授权范围是 workspace 内的“敏感类别 + 最高级别”，不是单个资产 ——
 * 这一点在表单和列表里都要明示，避免用户误以为只放开某张表。
 */
defineOptions({ name: 'AccessRequestView' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const error = ref('')
const mine = ref<AccessRequest[]>([])
const pending = ref<AccessRequest[]>([])
const grants = ref<AccessGrant[]>([])
const submitting = ref(false)
const formError = ref('')

const canRequest = computed(() => auth.hasPermission('metadata:access:request'))
const canApprove = computed(() => auth.hasPermission('metadata:access:approve'))

const categoryLabels: Record<AccessCategory, string> = {
  PII: '个人信息',
  FINANCIAL: '金融账户',
  MEDICAL: '医疗信息',
  CREDENTIAL: '账号凭据',
}

const statusMeta: Record<AccessRequestStatus, {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info'
}> = {
  PENDING: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已批准', type: 'success' },
  REJECTED: { label: '已拒绝', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
}

const grantMeta: Record<string, {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info'
}> = {
  ACTIVE: { label: '有效', type: 'success' },
  EXPIRED: { label: '已过期', type: 'info' },
  REVOKED: { label: '已撤销', type: 'danger' },
}

const applyVisible = ref(false)
const applyForm = reactive({
  category: 'PII' as AccessCategory,
  maxLevel: 'L3' as AccessLevel,
  durationHours: 8,
  reason: '',
})

const decisionVisible = ref(false)
const decisionAction = ref<'approve' | 'reject'>('approve')
const decisionNote = ref('')
const decisionTarget = ref<AccessRequest | null>(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const requests = await Promise.all([
      accessRequestApi.list('mine'),
      canApprove.value ? accessRequestApi.list('pending') : Promise.resolve([]),
      accessRequestApi.grants().catch(() => []),
    ])
    mine.value = requests[0]
    pending.value = requests[1]
    grants.value = requests[2]
  } catch {
    error.value = '访问申请加载失败'
  } finally {
    loading.value = false
  }
}

function openApply(prefill?: { category?: string; level?: string }) {
  applyForm.category = (prefill?.category as AccessCategory) || 'PII'
  applyForm.maxLevel = (prefill?.level as AccessLevel) || 'L3'
  applyForm.durationHours = 8
  applyForm.reason = ''
  formError.value = ''
  applyVisible.value = true
}

async function submitApply() {
  formError.value = ''
  if (applyForm.reason.trim().length < 10) {
    formError.value = '申请原因至少 10 字'
    return
  }
  submitting.value = true
  try {
    await accessRequestApi.create({
      category: applyForm.category,
      maxLevel: applyForm.maxLevel,
      reason: applyForm.reason.trim(),
      durationHours: applyForm.durationHours,
    })
    applyVisible.value = false
    ElMessage.success('申请已提交，等待审批')
    await load()
  } catch (e: any) {
    formError.value = e?.message || '提交失败'
  } finally {
    submitting.value = false
  }
}

function openDecision(request: AccessRequest | Record<string, any>, action: 'approve' | 'reject') {
  decisionTarget.value = request as AccessRequest
  decisionAction.value = action
  decisionNote.value = ''
  decisionVisible.value = true
}

async function submitDecision() {
  if (!decisionTarget.value) {
    return
  }
  submitting.value = true
  try {
    if (decisionAction.value === 'approve') {
      await accessRequestApi.approve(decisionTarget.value.id, decisionNote.value.trim() || undefined)
      ElMessage.success('已批准，grant 已生效')
    } else {
      await accessRequestApi.reject(decisionTarget.value.id, decisionNote.value.trim() || undefined)
      ElMessage.success('已拒绝')
    }
    decisionVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function cancelRequest(request: AccessRequest | Record<string, any>) {
  const ok = await confirmDanger('取消这条待审批申请？', '取消申请', '取消申请')
  if (!ok) {
    return
  }
  await accessRequestApi.cancel(request.id)
  ElMessage.success('申请已取消')
  await load()
}

async function revokeGrant(grant: AccessGrant | Record<string, any>) {
  const ok = await confirmDanger(
    `撤销 ${grant.username} 的 ${categoryLabel(grant.category)}（≤${grant.maxLevel}）授权？后续查询立即按原策略脱敏。`,
    '撤销授权',
    '撤销',
  )
  if (!ok) {
    return
  }
  await accessRequestApi.revoke(grant.id)
  ElMessage.success('授权已撤销')
  await load()
}

function requestStatus(request: AccessRequest | Record<string, any>) {
  return statusMeta[request.status as AccessRequestStatus] || statusMeta.PENDING
}

function grantStatus(grant: AccessGrant | Record<string, any>) {
  return grantMeta[grant.status] || grantMeta.EXPIRED
}

function categoryLabel(category: string): string {
  return categoryLabels[category as AccessCategory] || category
}

async function bootstrap() {
  await load()
  if (route.query.apply === '1') {
    openApply({
      category: typeof route.query.category === 'string' ? route.query.category : undefined,
      level: typeof route.query.level === 'string' ? route.query.level : undefined,
    })
    router.replace({ query: { ...route.query, apply: undefined } })
  }
}

usePageRefresh(bootstrap)
</script>

<template>
  <div class="page-container access-page">
    <PageHeader
      title="敏感访问"
      description="带原因、时限与审批的临时明文访问。授权在问数 Query Gateway 生效，导出复用同一链路。"
    >
      <template #actions>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canRequest" type="primary" @click="openApply()">申请临时访问</el-button>
      </template>
    </PageHeader>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="access-page__hint"
      title="P2 授权范围是当前空间内某一敏感类别、不超过所选级别的列，不绑定单个资产。"
    />

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      :title="error"
      class="access-page__alert"
    >
      <el-button link type="primary" @click="load">重试</el-button>
    </el-alert>

    <el-card shadow="never" class="access-page__card">
      <template #header>
        <div class="card-head">
          <span>我的申请</span>
          <el-tag size="small" type="info">{{ mine.length }}</el-tag>
        </div>
      </template>
      <el-table v-loading="loading" :data="mine" size="small" border>
        <el-table-column label="类别 / 级别" width="150">
          <template #default="{ row }">
            <el-tag size="small">{{ categoryLabel(row.category) }}</el-tag>
            <el-tag size="small" type="warning" style="margin-left: 4px">{{ row.maxLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="240" show-overflow-tooltip />
        <el-table-column label="时长" width="80" align="right">
          <template #default="{ row }">{{ row.durationHours }} h</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="requestStatus(row).type" size="small">
              {{ requestStatus(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审批人" width="110">
          <template #default="{ row }">{{ row.approver || '-' }}</template>
        </el-table-column>
        <el-table-column label="Grant" width="170">
          <template #default="{ row }">
            <template v-if="row.grant">
              <el-tag :type="grantStatus(row.grant).type" size="small">
                {{ grantStatus(row.grant).label }}
              </el-tag>
              <div class="text-muted">{{ formatDateTime(row.grant.expiresAt) }}</div>
            </template>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="168">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              link
              type="danger"
              @click="cancelRequest(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loading && !mine.length" description="还没有提交过访问申请">
        <el-button v-if="canRequest" type="primary" plain @click="openApply()">申请临时访问</el-button>
      </EmptyState>
    </el-card>

    <el-card v-if="canApprove" shadow="never" class="access-page__card">
      <template #header>
        <div class="card-head">
          <span>待审批</span>
          <el-tag size="small" type="warning">{{ pending.length }}</el-tag>
        </div>
      </template>
      <el-table v-loading="loading" :data="pending" size="small" border>
        <el-table-column prop="requester" label="申请人" width="120" />
        <el-table-column label="类别 / 级别" width="150">
          <template #default="{ row }">
            <el-tag size="small">{{ categoryLabel(row.category) }}</el-tag>
            <el-tag size="small" type="warning" style="margin-left: 4px">{{ row.maxLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="260" show-overflow-tooltip />
        <el-table-column label="时长" width="80" align="right">
          <template #default="{ row }">{{ row.durationHours }} h</template>
        </el-table-column>
        <el-table-column label="提交时间" width="168">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="success"
              :disabled="row.requester === auth.user?.username"
              @click="openDecision(row, 'approve')"
            >
              批准
            </el-button>
            <el-button link type="danger" @click="openDecision(row, 'reject')">拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loading && !pending.length" description="没有待审批的申请" />
    </el-card>

    <el-card shadow="never" class="access-page__card">
      <template #header>
        <div class="card-head">
          <span>{{ canApprove ? '全部授权' : '我的授权' }}</span>
          <el-tag size="small" type="info">{{ grants.length }}</el-tag>
        </div>
      </template>
      <el-table v-loading="loading" :data="grants" size="small" border>
        <el-table-column prop="username" label="用户" width="120" />
        <el-table-column label="类别 / 级别" width="150">
          <template #default="{ row }">
            <el-tag size="small">{{ categoryLabel(row.category) }}</el-tag>
            <el-tag size="small" type="warning" style="margin-left: 4px">{{ row.maxLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="grantStatus(row).type" size="small">
              {{ grantStatus(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到期时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="撤销" width="180">
          <template #default="{ row }">
            <span v-if="row.revokedAt" class="text-muted">
              {{ row.revokedBy || '-' }} / {{ formatDateTime(row.revokedAt) }}
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button
              v-if="canApprove && row.active"
              link
              type="danger"
              @click="revokeGrant(row)"
            >
              撤销
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loading && !grants.length" description="暂无访问授权" />
    </el-card>

    <el-dialog v-model="applyVisible" title="申请临时访问" width="540px">
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        class="dialog-alert"
        title="授权范围是当前空间内同类敏感数据，不限于某一个资产。请按最小必要申请。"
      />
      <el-form label-width="100px">
        <el-form-item label="敏感类别" required>
          <el-select v-model="applyForm.category" style="width: 100%">
            <el-option
              v-for="(label, value) in categoryLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="最高级别" required>
          <el-select v-model="applyForm.maxLevel" style="width: 100%">
            <el-option v-for="level in ['L1', 'L2', 'L3', 'L4']" :key="level" :label="level" :value="level" />
          </el-select>
        </el-form-item>
        <el-form-item label="时长" required>
          <el-input-number v-model="applyForm.durationHours" :min="1" :max="168" />
          <span class="text-muted" style="margin-left: 8px">1–168 小时</span>
        </el-form-item>
        <el-form-item label="申请原因" required>
          <el-input
            v-model="applyForm.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="至少 10 字，说明业务场景与为什么必须看明文"
          />
        </el-form-item>
        <el-alert
          v-if="formError"
          type="error"
          show-icon
          :closable="false"
          :title="formError"
        />
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="decisionVisible"
      :title="decisionAction === 'approve' ? '批准访问申请' : '拒绝访问申请'"
      width="480px"
    >
      <div v-if="decisionTarget" class="decision-summary">
        <div>{{ decisionTarget.requester }} · {{ categoryLabel(decisionTarget.category) }} ≤ {{ decisionTarget.maxLevel }}</div>
        <p>{{ decisionTarget.reason }}</p>
      </div>
      <el-input
        v-model="decisionNote"
        type="textarea"
        :rows="3"
        maxlength="512"
        placeholder="审批备注（可选）"
      />
      <template #footer>
        <el-button @click="decisionVisible = false">取消</el-button>
        <el-button
          :type="decisionAction === 'approve' ? 'primary' : 'danger'"
          :loading="submitting"
          @click="submitDecision"
        >
          {{ decisionAction === 'approve' ? '批准' : '拒绝' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.access-page__hint {
  margin-bottom: var(--dg-space-md);
}

.access-page__alert {
  margin-bottom: var(--dg-space-md);
}

.access-page__card {
  margin-bottom: var(--dg-space-lg);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
}

.dialog-alert {
  margin-bottom: var(--dg-space-md);
}

.decision-summary {
  margin-bottom: var(--dg-space-md);
  padding: var(--dg-space-sm);
  background: var(--dg-surface-muted);
  border-radius: var(--dg-radius);
  font-size: 13px;
}

.decision-summary p {
  margin: var(--dg-space-xs) 0 0;
  color: var(--dg-text-secondary);
}
</style>
