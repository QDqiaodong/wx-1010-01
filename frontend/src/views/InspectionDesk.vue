<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { InspectionOrder, Equipment, InspectionStatus } from '@/types'
import { INSPECTION_STATUS_MAP } from '@/types'
import { inspectionApi, equipmentApi } from '@/api'

const route = useRoute()

// ---------- 列表与筛选 ----------
const orders = ref<InspectionOrder[]>([])
const equipments = ref<Equipment[]>([])
const loading = ref(false)
const filterOpenOnly = ref(true)
const filterStatus = ref<InspectionStatus | ''>('')
const filterEquipmentId = ref<number | undefined>()
const detailOrder = ref<InspectionOrder | null>(null)
const detailVisible = ref(false)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const statusOptions = Object.entries(INSPECTION_STATUS_MAP).map(([value, m]) => ({
  value: value as InspectionStatus,
  label: m.label
}))

const loadOrders = async () => {
  loading.value = true
  try {
    orders.value = await inspectionApi.listOrders({
      openOnly: filterOpenOnly.value || undefined,
      status: filterStatus.value || undefined,
      equipmentId: filterEquipmentId.value
    })
  } finally {
    loading.value = false
  }
}

const loadEquipments = async () => {
  equipments.value = await equipmentApi.getAll()
}

onMounted(async () => {
  await Promise.all([loadOrders(), loadEquipments()])
  // 多工作人员协同：每 5 秒拉最新状态，旧页面提交由后端 409 兜底
  refreshTimer = setInterval(loadOrders, 5000)

  // 从器材管理页"送检"按钮带参直达
  const qId = Number(route.query.equipmentId)
  if (route.query.action === 'create' && Number.isFinite(qId) && qId > 0) {
    const target = equipments.value.find(e => e.id === qId)
    if (target && target.status !== 'SCRAPPED') {
      openCreate(qId)
    }
  }
})
onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

const equipmentName = (id: number) => {
  const e = equipments.value.find(x => x.id === id)
  return e ? `${e.equipmentCode} ${e.name}` : `器材#${id}`
}

const handleFilterChange = () => loadOrders()

// ---------- 登记送检 ----------
const createVisible = ref(false)
const creating = ref(false)
const createForm = ref({ equipmentId: undefined as number | undefined, problemDescription: '', reporter: '' })

const openCreate = (equipmentId?: number) => {
  createForm.value = {
    equipmentId,
    problemDescription: '',
    reporter: localStorage.getItem('inspection_operator') || ''
  }
  createVisible.value = true
}

const bindableEquipments = computed(() =>
  // 已报废的不出现在送检选择里；已有未关闭单据的由后端 409 拦截，这里也标注
  equipments.value.filter(e => e.status !== 'SCRAPPED')
)

const handleCreate = async () => {
  if (!createForm.value.equipmentId) {
    ElMessage.error('请选择要送检的器材')
    return
  }
  if (!createForm.value.problemDescription.trim()) {
    ElMessage.error('请填写现场发现的问题描述')
    return
  }
  if (!createForm.value.reporter.trim()) {
    ElMessage.error('请填写发现人')
    return
  }
  localStorage.setItem('inspection_operator', createForm.value.reporter.trim())
  creating.value = true
  try {
    const order = await inspectionApi.create(createForm.value.equipmentId, {
      problemDescription: createForm.value.problemDescription.trim(),
      reporter: createForm.value.reporter.trim()
    })
    ElMessage.success(
      order.status === 'PENDING_RETURN'
        ? '送检已登记：器材还在游客手上，状态为「待归还」，游客归还后将自动进入维修队列'
        : '送检成功，器材已进入维修队列并从绑定/发装清单冻结'
    )
    createVisible.value = false
    await Promise.all([loadOrders(), loadEquipments()])
    openDetail(order.id)
  } catch (e: any) {
    ElMessage({ type: e?.response?.status === 409 ? 'warning' : 'error', message: e?.response?.data?.error || '送检失败' })
  } finally {
    creating.value = false
  }
}

// ---------- 通用动作对话框（处理人 + 备注一次填写） ----------
const actionDialog = ref({
  visible: false,
  title: '',
  label: '',
  required: false,
  confirmText: '',
  confirmType: 'primary' as 'primary' | 'success' | 'warning' | 'danger',
  handler: '',
  remark: '',
  run: (() => Promise.resolve({} as InspectionOrder)) as (handler: string, remark: string) => Promise<InspectionOrder>,
  successHint: ''
})

const promptAction = (opts: {
  order: InspectionOrder
  title: string
  label: string
  required: boolean
  defaultRemark?: string
  confirmText: string
  confirmType?: 'primary' | 'success' | 'warning' | 'danger'
  run: (handler: string, remark: string) => Promise<InspectionOrder>
  successHint?: string
}) => {
  actionDialog.value = {
    visible: true,
    title: opts.title,
    label: opts.label,
    required: opts.required,
    confirmText: opts.confirmText,
    confirmType: opts.confirmType || 'primary',
    handler: localStorage.getItem('inspection_handler') || '',
    remark: opts.defaultRemark || '',
    run: opts.run,
    successHint: opts.successHint || '操作成功'
  }
}

const actionSubmitting = ref(false)
const handleActionConfirm = async () => {
  const d = actionDialog.value
  if (!d.handler.trim()) {
    ElMessage.error('请填写处理人')
    return
  }
  if (d.required && !d.remark.trim()) {
    ElMessage.error('请填写必填内容')
    return
  }
  localStorage.setItem('inspection_handler', d.handler.trim())
  actionSubmitting.value = true
  try {
    const updated = await d.run(d.handler.trim(), d.remark.trim())
    ElMessage.success(d.successHint)
    d.visible = false
    await Promise.all([loadOrders(), loadEquipments()])
    if (detailVisible.value) await openDetail(updated.id, true)
  } catch (e: any) {
    // 409：旧页面/多人协同冲突，后端拒绝且未覆盖新状态；400：校验失败
    const status = e?.response?.status
    ElMessage({
      type: status === 409 ? 'warning' : 'error',
      message: e?.response?.data?.error || '操作失败'
    })
    if (status === 409) {
      d.visible = false
      await loadOrders()
    }
  } finally {
    actionSubmitting.value = false
  }
}

const act = (
  order: InspectionOrder,
  action: 'transfer' | 'requestInfo' | 'resubmit' | 'submitRe' | 'pass' | 'fail' | 'scrap'
) => {
  const common = { order }
  switch (action) {
    case 'transfer':
      promptAction({
        ...common,
        title: `转入待处理（送检单 #${order.id}）`,
        label: '游客无法归还的情况说明（选填）',
        required: false,
        confirmText: '确认转入待处理',
        run: (handler, remark) => inspectionApi.transferPending(order.id, { handler, remark }),
        successHint: '已转入待处理，原流水闭环，器材进入维修队列'
      })
      break
    case 'requestInfo':
      promptAction({
        ...common,
        title: `退回补充材料（送检单 #${order.id}）`,
        label: '请说明需要发现人补充哪些材料',
        required: true,
        confirmText: '退回',
        run: (handler, remark) => inspectionApi.requestInfo(order.id, { handler, remark }),
        successHint: '已退回，等待发现人补充材料'
      })
      break
    case 'resubmit':
      promptAction({
        ...common,
        title: `补充材料后重新提交（送检单 #${order.id}）`,
        label: '请填写补充的材料/说明',
        required: true,
        confirmText: '重新提交',
        run: (handler, remark) => inspectionApi.resubmit(order.id, { handler, remark }),
        successHint: '已重新提交，单据回到维修队列'
      })
      break
    case 'submitRe':
      promptAction({
        ...common,
        title: `提交复检（送检单 #${order.id}）`,
        label: '维修处理说明（随单留痕，选填）',
        required: false,
        confirmText: '提交复检',
        run: (handler, remark) => inspectionApi.submitReinspection(order.id, { handler, remark }),
        successHint: '已提交复检'
      })
      break
    case 'pass':
      promptAction({
        ...common,
        title: `复检通过（送检单 #${order.id}）`,
        label: '复检结论说明（选填）',
        required: false,
        defaultRemark: '复检通过，器材恢复可用',
        confirmText: '确认通过放行',
        run: (handler, remark) => inspectionApi.reinspectionPass(order.id, { handler, remark }),
        successHint: '复检通过，器材已重新回到可用池'
      })
      break
    case 'fail':
      promptAction({
        ...common,
        title: `复检不通过（送检单 #${order.id}）`,
        label: '必须填写复检不通过的具体原因（将留痕，退回维修）',
        required: true,
        confirmText: '确认不通过，退回维修',
        run: (handler, remark) => inspectionApi.reinspectionFail(order.id, { handler, remark }),
        successHint: '复检不通过，单据已退回维修队列'
      })
      break
    case 'scrap':
      promptAction({
        ...common,
        title: `判定报废（送检单 #${order.id}）`,
        label: '必须填写报废原因。报废后器材永久退出可绑定/可发装清单，历史记录保留',
        required: true,
        confirmText: '确认报废',
        confirmType: 'danger',
        run: (handler, remark) => inspectionApi.scrap(order.id, { handler, remark }),
        successHint: '器材已报废并从可绑定清单移除，历史已保留'
      })
      break
  }
}

// ---------- 详情（完整痕迹时间线） ----------
const openDetail = async (id: number, silent = false) => {
  try {
    detailOrder.value = await inspectionApi.getOrder(id)
    detailVisible.value = true
  } catch (e: any) {
    if (!silent) ElMessage.error(e?.response?.data?.error || '查询送检单失败')
  }
}

const fmtTime = (t?: string) => (t ? t.replace('T', ' ').slice(0, 19) : '—')
const tagType = (s: InspectionStatus) => INSPECTION_STATUS_MAP[s].type
</script>

<template>
  <div class="inspection-desk">
    <div class="header">
      <h2>器材送检台</h2>
      <div class="filters">
        <el-select v-model="filterOpenOnly" style="width: 150px" @change="handleFilterChange">
          <el-option :value="true" label="未关闭单据" />
          <el-option :value="false" label="全部单据" />
        </el-select>
        <el-select
          v-model="filterStatus"
          placeholder="全部状态"
          clearable
          style="width: 150px"
          @change="handleFilterChange"
        >
          <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select
          v-model="filterEquipmentId"
          placeholder="按器材筛选"
          clearable
          filterable
          style="width: 240px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="e in equipments"
            :key="e.id"
            :label="`${e.equipmentCode} ${e.name}`"
            :value="e.id"
          />
        </el-select>
        <el-button @click="loadOrders">刷新</el-button>
        <el-button type="primary" @click="openCreate()">登记送检</el-button>
      </div>
    </div>

    <el-alert
      class="hint-bar"
      type="info"
      :closable="false"
      show-icon
      title="送检后器材立即停止新的场次绑定与发装；已在游客手上的器材按原流水归还或转入待处理。复检通过才回可用池，报废保留全部历史并从可绑定清单消失。"
    />

    <el-table :data="orders" border v-loading="loading" empty-text="暂无送检单">
      <el-table-column prop="id" label="单号" width="70" />
      <el-table-column label="器材" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row.id)">{{ equipmentName(row.equipmentId) }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="problemDescription" label="问题描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="reporter" label="发现人" width="90" />
      <el-table-column label="送检时间" width="155">
        <template #default="{ row }">{{ fmtTime(row.reportTime) }}</template>
      </el-table-column>
      <el-table-column prop="handler" label="处理人" width="90">
        <template #default="{ row }">{{ row.handler || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)">{{ row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row.id)">痕迹</el-button>
          <template v-if="row.status === 'PENDING_RETURN'">
            <el-button size="small" type="warning" @click="act(row, 'transfer')">
              转入待处理
            </el-button>
          </template>
          <template v-else-if="row.status === 'SUBMITTED'">
            <el-button size="small" @click="act(row, 'requestInfo')">退补材料</el-button>
            <el-button size="small" type="primary" @click="act(row, 'submitRe')">提交复检</el-button>
            <el-button size="small" type="danger" plain @click="act(row, 'scrap')">报废</el-button>
          </template>
          <template v-else-if="row.status === 'INFO_NEEDED'">
            <el-button size="small" type="primary" @click="act(row, 'resubmit')">补充后重提</el-button>
            <el-button size="small" type="danger" plain @click="act(row, 'scrap')">报废</el-button>
          </template>
          <template v-else-if="row.status === 'REINSPECTING'">
            <el-button size="small" type="success" @click="act(row, 'pass')">复检通过</el-button>
            <el-button size="small" type="warning" @click="act(row, 'fail')">复检不通过</el-button>
            <el-button size="small" type="danger" plain @click="act(row, 'scrap')">报废</el-button>
          </template>
          <template v-else>
            <el-tag size="small" :type="row.status === 'PASSED' ? 'success' : 'danger'">
              {{ row.status === 'PASSED' ? '已放行' : '已报废' }}
            </el-tag>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 登记送检弹窗 -->
    <el-dialog v-model="createVisible" title="登记器材送检" width="520px">
      <el-form label-width="100px">
        <el-form-item label="送检器材" required>
          <el-select v-model="createForm.equipmentId" filterable placeholder="选择现场发现异常的器材" style="width: 100%">
            <el-option
              v-for="e in bindableEquipments"
              :key="e.id"
              :label="`${e.equipmentCode} ${e.name}（${e.status === 'INSPECTION' ? '送检中' : e.status === 'IN_USE' ? '使用中' : '可用'}）`"
              :value="e.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="问题描述" required>
          <el-input v-model="createForm.problemDescription" type="textarea" :rows="3" placeholder="现场发现的异常现象，提交后不可修改（补充材料另行追加）" />
        </el-form-item>
        <el-form-item label="发现人" required>
          <el-input v-model="createForm.reporter" placeholder="工作人员姓名/工号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">确认送检</el-button>
      </template>
    </el-dialog>

    <!-- 维修动作对话框：处理人 + 备注 -->
    <el-dialog v-model="actionDialog.visible" :title="actionDialog.title" width="500px">
      <el-form label-width="92px">
        <el-form-item label="处理人" required>
          <el-input v-model="actionDialog.handler" placeholder="维修/复检工作人员姓名/工号" />
        </el-form-item>
        <el-form-item :label="actionDialog.required ? '必填内容' : '备注'" :required="actionDialog.required">
          <el-input v-model="actionDialog.remark" type="textarea" :rows="4" :placeholder="actionDialog.label" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionDialog.visible = false">取消</el-button>
        <el-button :type="actionDialog.confirmType" :loading="actionSubmitting" @click="handleActionConfirm">
          {{ actionDialog.confirmText }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 送检单详情 + 痕迹时间线 -->
    <el-dialog v-model="detailVisible" title="送检单详情与操作痕迹" width="720px">
      <template v-if="detailOrder">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="单号">#{{ detailOrder.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="tagType(detailOrder.status)">{{ detailOrder.statusLabel }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="器材">{{ equipmentName(detailOrder.equipmentId) }}</el-descriptions-item>
          <el-descriptions-item label="发现人">{{ detailOrder.reporter }}</el-descriptions-item>
          <el-descriptions-item label="送检时间">{{ fmtTime(detailOrder.reportTime) }}</el-descriptions-item>
          <el-descriptions-item label="当前处理人">{{ detailOrder.handler || '—' }}</el-descriptions-item>
          <el-descriptions-item label="问题描述" :span="2">{{ detailOrder.problemDescription }}</el-descriptions-item>
          <el-descriptions-item v-if="detailOrder.conclusion" label="结论" :span="2">
            {{ detailOrder.conclusion }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detailOrder.closeTime" label="关闭时间" :span="2">
            {{ fmtTime(detailOrder.closeTime) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="timeline-title">操作痕迹（{{ detailOrder.actionLogs?.length || 0 }} 条，历史不覆盖）</h4>
        <el-timeline>
          <el-timeline-item
            v-for="log in detailOrder.actionLogs"
            :key="log.id"
            :timestamp="`${fmtTime(log.actionTime)} · ${log.operator}`"
            placement="top"
            :type="log.actionType === 'REINSPECTION_PASS' ? 'success' : log.actionType === 'SCRAP' ? 'danger' : log.actionType === 'REINSPECTION_FAIL' ? 'warning' : 'primary'"
          >
            <b>{{ log.actionTypeLabel }}</b>
            <span class="muted">
              （{{ log.fromStatusLabel || '—' }} → {{ log.toStatusLabel }}<template v-if="log.dispatchRecordId">，流水 #{{ log.dispatchRecordId }}</template>）
            </span>
            <div v-if="log.remark" class="log-remark">{{ log.remark }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.inspection-desk {
  background: white;
  border-radius: 8px;
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 10px;
}

.header h2 {
  margin: 0;
}

.filters {
  display: flex;
  gap: 10px;
  align-items: center;
}

.hint-bar {
  margin-bottom: 14px;
}

.timeline-title {
  margin: 18px 0 12px;
}

.log-remark {
  color: #606266;
  margin-top: 2px;
}

.muted {
  color: #909399;
  font-size: 12px;
}
</style>
