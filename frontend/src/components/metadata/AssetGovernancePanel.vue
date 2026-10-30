<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { governanceApi } from '@/api/catalog-governance'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type {
  AssetGovernance,
  BusinessDomain,
  CertificationStatus,
  GovernanceMember,
} from '@/types/catalog-governance'

/**
 * 资产治理面板。
 *
 * 展示与编辑分开：只有 metadata:asset:govern 才渲染“编辑治理”。
 * 认证徽标只是“是否经过人工认证”，不等于质量通过（质量分由质量 Tab 提供）。
 */
const props = defineProps<{
  assetId: string
  canEdit: boolean
}>()

const emit = defineEmits<{ saved: [value: AssetGovernance] }>()

const loading = ref(false)
const error = ref('')
const governance = ref<AssetGovernance | null>(null)
const domains = ref<BusinessDomain[]>([])
const members = ref<GovernanceMember[]>([])

const editVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
const form = reactive({
  domainId: '',
  ownerUsername: '',
  stewardUsername: '',
  certificationStatus: 'NONE' as CertificationStatus,
  certificationNote: '',
  reviewDueAt: '',
})

const domainDialogVisible = ref(false)
const domainSubmitting = ref(false)
const domainError = ref('')
const editingDomainId = ref('')
const domainForm = reactive({ code: '', name: '', description: '' })

const certificationMeta: Record<CertificationStatus, {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info'
}> = {
  CERTIFIED: { label: '已认证', type: 'success' },
  DEPRECATED: { label: '已废弃', type: 'danger' },
  NONE: { label: '未认证', type: 'info' },
}

const cert = computed(() => certificationMeta[governance.value?.certificationStatus ?? 'NONE'])
const isReviewDue = computed(() => {
  const due = governance.value?.reviewDueAt
  return !!due && new Date(due).getTime() < Date.now()
})
const certificationItems = computed(() => {
  const value = governance.value
  if (!value) {
    return []
  }
  return [
    { label: '业务域', value: value.domainName || '未配置' },
    { label: '负责人', value: value.ownerUsername || '未配置' },
    { label: '数据管家', value: value.stewardUsername || '未配置' },
    { label: '认证状态', value: cert.value.label },
    { label: '认证人 / 时间', value: value.certifiedBy
      ? `${value.certifiedBy} / ${formatDateTime(value.certifiedAt)}`
      : '-' },
    { label: '复审日期', value: value.reviewDueAt
      ? `${formatDateTime(value.reviewDueAt)}${isReviewDue.value ? '（待复审）' : ''}`
      : '未设置' },
    { label: '最近更新', value: value.updatedBy
      ? `${value.updatedBy} / ${formatDateTime(value.updatedAt)}`
      : '-' },
  ]
})

async function load() {
  if (!props.assetId) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    governance.value = await governanceApi.getAssetGovernance(props.assetId)
  } catch {
    error.value = '治理信息加载失败'
    governance.value = null
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  if (!props.canEdit) {
    return
  }
  const [domainList, memberList] = await Promise.all([
    governanceApi.listDomains().catch(() => []),
    governanceApi.listMembers().catch(() => []),
  ])
  domains.value = domainList
  members.value = memberList
}

function openEdit() {
  const value = governance.value
  Object.assign(form, {
    domainId: value?.domainId || '',
    ownerUsername: value?.ownerUsername || '',
    stewardUsername: value?.stewardUsername || '',
    certificationStatus: value?.certificationStatus || 'NONE',
    certificationNote: value?.certificationNote || '',
    reviewDueAt: value?.reviewDueAt || '',
  })
  formError.value = ''
  editVisible.value = true
  void loadOptions()
}

async function submit() {
  formError.value = ''
  if (form.certificationStatus === 'DEPRECATED' && !form.certificationNote.trim()) {
    formError.value = '废弃资产必须填写迁移/废弃说明'
    return
  }
  submitting.value = true
  try {
    const saved = await governanceApi.updateAssetGovernance(props.assetId, {
      domainId: form.domainId || null,
      ownerUsername: form.ownerUsername || null,
      stewardUsername: form.stewardUsername || null,
      certificationStatus: form.certificationStatus,
      certificationNote: form.certificationNote.trim() || null,
      reviewDueAt: form.reviewDueAt
        ? new Date(form.reviewDueAt).toISOString()
        : null,
    })
    governance.value = saved
    editVisible.value = false
    ElMessage.success('资产治理已保存')
    emit('saved', saved)
  } catch (e: any) {
    formError.value = e?.message || '保存失败'
  } finally {
    submitting.value = false
  }
}

function ownerOptionLabel(username: string): string {
  const member = members.value.find((item) => item.username === username)
  return member?.displayName ? `${member.displayName}（${username}）` : username
}

function openDomainDialog(domain?: BusinessDomain | Record<string, any>) {
  const value = domain as BusinessDomain | undefined
  editingDomainId.value = value?.id || ''
  Object.assign(domainForm, {
    code: value?.code || '',
    name: value?.name || '',
    description: value?.description || '',
  })
  domainError.value = ''
  domainDialogVisible.value = true
}

async function submitDomain() {
  domainError.value = ''
  if (!domainForm.code.trim() || !domainForm.name.trim()) {
    domainError.value = 'code 与名称不能为空'
    return
  }
  domainSubmitting.value = true
  try {
    if (editingDomainId.value) {
      await governanceApi.updateDomain(editingDomainId.value, {
        code: domainForm.code.trim(),
        name: domainForm.name.trim(),
        description: domainForm.description.trim() || undefined,
      })
    } else {
      await governanceApi.createDomain({
        code: domainForm.code.trim(),
        name: domainForm.name.trim(),
        description: domainForm.description.trim() || undefined,
      })
    }
    domainDialogVisible.value = false
    domains.value = await governanceApi.listDomains()
    ElMessage.success('业务域已保存')
  } catch (e: any) {
    domainError.value = e?.message || '保存失败'
  } finally {
    domainSubmitting.value = false
  }
}

async function removeDomain(domain: BusinessDomain | Record<string, any>) {
  const value = domain as BusinessDomain
  const ok = await confirmDanger(
    `删除业务域「${value.name}」？仍被资产引用时会被拒绝。`,
    '删除业务域',
  )
  if (!ok) {
    return
  }
  try {
    await governanceApi.removeDomain(value.id)
    domains.value = await governanceApi.listDomains()
    ElMessage.success('业务域已删除')
  } catch {
    // 错误消息由请求拦截器统一提示
  }
}

watch(() => props.assetId, load)

onMounted(load)
</script>

<template>
  <section class="asset-governance" v-loading="loading">
    <div class="asset-governance__head">
      <h4>治理</h4>
      <div class="asset-governance__actions">
        <el-tag :type="cert.type" size="small">{{ cert.label }}</el-tag>
        <el-tag v-if="isReviewDue" type="warning" size="small">待复审</el-tag>
        <el-button v-if="canEdit" link type="primary" @click="openEdit">
          编辑治理
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!governance?.ownerActive || !governance?.stewardActive"
      class="asset-governance__alert"
      type="warning"
      show-icon
      :closable="false"
      title="负责人/管家已不在当前空间，请重新指派"
    />
    <el-alert
      v-if="error"
      class="asset-governance__alert"
      type="warning"
      show-icon
      :closable="false"
      :title="error"
    />
    <el-alert
      v-if="governance?.certificationStatus === 'DEPRECATED' && governance.certificationNote"
      class="asset-governance__alert"
      type="error"
      show-icon
      :closable="false"
      :title="`已废弃：${governance.certificationNote}`"
    />

    <dl class="asset-governance__list">
      <div v-for="item in certificationItems" :key="item.label">
        <dt>{{ item.label }}</dt>
        <dd>{{ item.value }}</dd>
      </div>
    </dl>
    <p class="asset-governance__hint">
      认证表示“人工确认可用”，不等于质量通过；质量分只覆盖已配置的质量规则。
    </p>

    <el-drawer v-model="editVisible" title="编辑资产治理" size="560px">
      <el-form label-width="110px">
        <el-form-item label="业务域">
          <div class="asset-governance__domain-row">
            <el-select v-model="form.domainId" clearable placeholder="未配置" style="flex: 1">
              <el-option
                v-for="domain in domains"
                :key="domain.id"
                :label="`${domain.name}（${domain.code}）`"
                :value="domain.id"
              />
            </el-select>
            <el-button @click="openDomainDialog()">维护业务域</el-button>
          </div>
        </el-form-item>
        <el-form-item label="负责人">
          <el-select
            v-model="form.ownerUsername"
            clearable
            filterable
            allow-create
            default-first-option
            placeholder="选择当前空间成员"
            style="width: 100%"
          >
            <el-option
              v-for="member in members"
              :key="member.id"
              :label="ownerOptionLabel(member.username)"
              :value="member.username"
            />
            <el-option
              v-if="form.ownerUsername && !members.some((m) => m.username === form.ownerUsername)"
              :label="`${form.ownerUsername}（已不在当前空间）`"
              :value="form.ownerUsername"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数据管家">
          <el-select
            v-model="form.stewardUsername"
            clearable
            filterable
            allow-create
            default-first-option
            placeholder="选择当前空间成员"
            style="width: 100%"
          >
            <el-option
              v-for="member in members"
              :key="member.id"
              :label="ownerOptionLabel(member.username)"
              :value="member.username"
            />
            <el-option
              v-if="form.stewardUsername && !members.some((m) => m.username === form.stewardUsername)"
              :label="`${form.stewardUsername}（已不在当前空间）`"
              :value="form.stewardUsername"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="认证状态">
          <el-radio-group v-model="form.certificationStatus">
            <el-radio-button value="NONE">未认证</el-radio-button>
            <el-radio-button value="CERTIFIED">已认证</el-radio-button>
            <el-radio-button value="DEPRECATED">已废弃</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="复审日期">
          <el-date-picker
            v-model="form.reviewDueAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="可选，仅提醒，不阻断查询"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="认证/废弃说明">
          <el-input
            v-model="form.certificationNote"
            type="textarea"
            :rows="3"
            maxlength="1024"
            show-word-limit
            placeholder="废弃状态必填；说明迁移原因或替代资产"
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
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="domainDialogVisible" title="维护业务域" width="680px">
      <div class="asset-governance__domain-toolbar">
        <el-button type="primary" plain @click="openDomainDialog()">新建业务域</el-button>
      </div>
      <el-table :data="domains" size="small" border>
        <el-table-column prop="code" label="code" width="120" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="assetCount" label="引用资产" width="96" align="right" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDomainDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="removeDomain(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider />
      <el-form label-width="90px">
        <el-form-item label="code" required>
          <el-input v-model="domainForm.code" placeholder="小写字母/数字/下划线，如 sales" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="domainForm.name" placeholder="如 销售" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="domainForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-alert
          v-if="domainError"
          type="error"
          show-icon
          :closable="false"
          :title="domainError"
        />
      </el-form>
      <template #footer>
        <el-button @click="domainDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="domainSubmitting" @click="submitDomain">
          {{ editingDomainId ? '保存修改' : '创建业务域' }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.asset-governance {
  margin-top: var(--dg-space-lg);
  padding-top: var(--dg-space-md);
  border-top: 1px solid var(--dg-border);
}

.asset-governance__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-md);
  margin-bottom: var(--dg-space-sm);
}

.asset-governance__head h4 {
  margin: 0;
  font-size: 14px;
}

.asset-governance__actions {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
}

.asset-governance__alert {
  margin-bottom: var(--dg-space-sm);
}

.asset-governance__list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--dg-space-sm) var(--dg-space-lg);
  margin: 0;
}

.asset-governance__list dt {
  font-size: 12px;
  color: var(--dg-text-muted);
  margin-bottom: 2px;
}

.asset-governance__list dd {
  margin: 0;
  font-size: 13px;
  color: var(--dg-text);
  word-break: break-all;
}

.asset-governance__hint {
  margin: var(--dg-space-md) 0 0;
  font-size: 12px;
  color: var(--dg-text-muted);
}

.asset-governance__domain-row {
  display: flex;
  gap: var(--dg-space-sm);
  width: 100%;
}

.asset-governance__domain-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--dg-space-sm);
}
</style>
