<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type UploadFile } from 'element-plus'
import { artifactApi } from '@/api/job'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type { Artifact } from '@/types/job'

/**
 * 文件管理(序号 32)—— 作业制品仓库。
 *
 * <b>菜单在「基础配置」下,归属却是 Runtime</b>(架构风险 R2)。这里存的是
 * 作业要执行的 JAR / Python 包,生命周期与执行绑定 —— 被运行中的任务引用的
 * 包不能删。把它当成"上传的文件"来管理,就会有人顺手删掉一个跑了三个月的
 * 实时任务正在用的包。
 */

const loading = ref(false)
const rows = ref<Artifact[]>([])
const total = ref(0)
const uploadVisible = ref(false)
const submitting = ref(false)
const selectedFile = ref<File | null>(null)

const query = reactive({ page: 1, size: 20, type: '', keyword: '' })
const form = reactive({ name: '', version: '1.0.0', type: 'JAR', description: '' })

const TYPES = ['JAR', 'PYTHON', 'SQL', 'OTHER']

function formatSize(bytes: number | null): string {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function load() {
  loading.value = true
  try {
    const page = await artifactApi.list({
      page: query.page,
      size: query.size,
      type: query.type || undefined,
      keyword: query.keyword || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onUpload() {
  Object.assign(form, { name: '', version: '1.0.0', type: 'JAR', description: '' })
  selectedFile.value = null
  uploadVisible.value = true
}

/** el-upload 的手动模式:选中文件先留在本地,点确定才真正上传 */
function onFileChange(file: UploadFile) {
  if (!file.raw) {
    return
  }
  selectedFile.value = file.raw
  if (!form.name) {
    // 用文件名(去扩展名)做默认制品名 —— 十有八九就是它
    form.name = file.name.replace(/\.[^.]+$/, '')
  }
  const ext = file.name.split('.').pop()?.toUpperCase()
  if (ext === 'JAR') form.type = 'JAR'
  else if (ext === 'PY') form.type = 'PYTHON'
  else if (ext === 'SQL') form.type = 'SQL'
}

async function submit() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('请填写制品名称')
    return
  }
  submitting.value = true
  try {
    await artifactApi.upload(selectedFile.value, {
      name: form.name.trim(),
      version: form.version,
      type: form.type,
      description: form.description || undefined,
    })
    ElMessage.success('上传成功')
    uploadVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: Artifact) {
  const confirmed = await confirmDanger(
    `确定删除「${row.name}:${row.version}」吗?磁盘上的文件会保留,以便事故复盘。`,
    '删除制品',
  )
  if (!confirmed) {
    return
  }
  await artifactApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

function onDownload(row: Artifact) {
  window.open(artifactApi.downloadUrl(row.id), '_blank')
}

function onFilterChange() {
  query.page = 1
  load()
}
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-select
            v-model="query.type"
            placeholder="全部类型"
            clearable
            style="width: 140px"
            @change="onFilterChange"
          >
            <el-option v-for="t in TYPES" :key="t" :label="t" :value="t" />
          </el-select>
          <el-input
            v-model="query.keyword"
            placeholder="搜索制品名称"
            clearable
            style="width: 200px"
            @keyup.enter="onFilterChange"
            @clear="onFilterChange"
          />
          <el-button @click="onFilterChange">查询</el-button>
        </div>
        <el-button v-permission="'runtime:artifact:manage'" type="primary" @click="onUpload">
          上传制品
        </el-button>
      </div>

      <el-alert type="info" :closable="false" show-icon style="margin: 12px 0">
        <template #title>制品不可变:同名同版本只能上传一次</template>
        要改内容就发新版本。可变的制品意味着"上周跑成功的那次执行"今天再跑
        可能是另一个结果 —— 而那正是可复现性要排除的东西。
      </el-alert>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row as Artifact).name }}
            <el-tag
              v-if="(row as Artifact).platformLevel"
              size="small"
              type="info"
              style="margin-left: 6px"
            >
              平台级
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="version" label="版本" width="110" />
        <el-table-column prop="type" label="类型" width="90" />

        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize((row as Artifact).sizeBytes) }}</template>
        </el-table-column>

        <el-table-column label="引用" width="90">
          <template #default="{ row }">
            <el-tooltip content="被多少个任务定义引用。大于 0 时不可删除">
              <el-tag :type="(row as Artifact).refCount > 0 ? 'warning' : 'info'" size="small">
                {{ (row as Artifact).refCount }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="摘要" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">
              {{ (row as Artifact).checksumSha256?.slice(0, 12) ?? '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime((row as Artifact).createdAt) }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onDownload(row as Artifact)">下载</el-button>
            <el-button
              v-permission="'runtime:artifact:manage'"
              link
              type="danger"
              :disabled="(row as Artifact).refCount > 0"
              @click="onDelete(row as Artifact)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="text-muted" style="padding: 24px">
            还没有制品。SQL 形态的作业不需要制品,JAR / Python 作业才需要。
          </div>
        </template>
      </el-table>

      <el-pagination
        :current-page="query.page"
        :page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="(p: number) => { query.page = p; load() }"
        @size-change="(s: number) => { query.size = s; query.page = 1; load() }"
      />
    </el-card>

    <el-dialog v-model="uploadVisible" title="上传制品" width="560px" destroy-on-close>
      <el-form :model="form" label-width="110px">
        <el-form-item label="文件" required>
          <el-upload
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :show-file-list="true"
          >
            <el-button>选择文件</el-button>
            <template #tip>
              <div class="text-muted">单个制品上限 512 MB</div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="制品名称" required>
          <el-input v-model="form.name" placeholder="与版本一起唯一" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="form.version" placeholder="如 1.0.0" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="t in TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>
