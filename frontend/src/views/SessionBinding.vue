<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElButton } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import type { Equipment, SessionEquipment, Session, EquipmentStatus } from '@/types'
import { AGE_GROUP_MAP, EQUIPMENT_STATUS_MAP } from '@/types'
import { equipmentApi, sessionApi, sessionEquipmentApi, apiError } from '@/api'

const route = useRoute()
const router = useRouter()
const sessionId = Number(route.params.id)

const session = ref<Session | null>(null)
const allEquipments = ref<Equipment[]>([])
const sessionEquipments = ref<SessionEquipment[]>([])

/** 可绑定清单：未绑定 + 非送检中 + 非已报废（后端会再次校验，防止旧页面停留期间状态变化） */
const availableEquipments = computed(() => {
  const boundIds = new Set(sessionEquipments.value.map(se => se.equipmentId))
  return allEquipments.value.filter(e =>
    !boundIds.has(e.id) && e.status !== 'INSPECTION' && e.status !== 'SCRAPPED')
})

/** 被排除的送检/报废器材单独统计，便于工作人员感知 */
const excludedEquipments = computed(() => {
  const boundIds = new Set(sessionEquipments.value.map(se => se.equipmentId))
  return allEquipments.value.filter(e =>
    !boundIds.has(e.id) && (e.status === 'INSPECTION' || e.status === 'SCRAPPED'))
})

const boundEquipmentDetails = computed(() => {
  const equipmentMap = new Map(allEquipments.value.map(e => [e.id, e]))
  return sessionEquipments.value.map(se => ({
    ...se,
    equipment: equipmentMap.get(se.equipmentId)
  }))
})

const loadData = async () => {
  session.value = await sessionApi.getById(sessionId)
  allEquipments.value = await equipmentApi.getAll()
  sessionEquipments.value = await sessionEquipmentApi.getBySession(sessionId)
}

onMounted(loadData)

const handleBind = async (equipmentId: number) => {
  const equipment = allEquipments.value.find(e => e.id === equipmentId)
  if (!equipment) return

  try {
    await sessionEquipmentApi.bind(sessionId, equipmentId, equipment.ageGroup)
    ElMessage.success('绑定成功')
    await loadData()
  } catch (e: any) {
    // 旧页面停留期间器材已被送检/报废：明确提示，并重新拉取以保留/刷新原有绑定流水
    ElMessage({
      type: e?.response?.status === 409 ? 'warning' : 'error',
      message: apiError(e, '绑定失败'),
      duration: 6000
    })
    await loadData()
  }
}

const handleUnbind = async (equipmentId: number) => {
  try {
    await sessionEquipmentApi.unbind(sessionId, equipmentId)
    ElMessage.success('解绑成功')
    await loadData()
  } catch (e: any) {
    ElMessage({
      type: e?.response?.status === 409 ? 'warning' : 'error',
      message: apiError(e, '解绑失败'),
      duration: 6000
    })
    await loadData()
  }
}

const handleAutoBind = async () => {
  try {
    await sessionEquipmentApi.autoBind(sessionId)
    ElMessage.success('自动绑定完成（送检中/已报废器材已自动跳过）')
    await loadData()
  } catch (e: any) {
    ElMessage.error(apiError(e, '自动绑定失败'))
    await loadData()
  }
}

const goBack = () => {
  router.push('/session')
}

const statusTagType: Record<EquipmentStatus, 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
  AVAILABLE: 'success',
  IN_USE: 'warning',
  MAINTENANCE: 'info',
  INSPECTION: 'danger',
  SCRAPPED: 'info'
}
</script>

<template>
  <div class="session-binding">
    <div class="header">
      <el-button @click="goBack">返回</el-button>
      <h2>{{ session?.sessionName }} - 器材绑定</h2>
      <el-button type="primary" @click="handleAutoBind">自动绑定</el-button>
    </div>

    <div class="content">
      <div class="panel">
        <h3>可绑定器材（已排除送检中/已报废）</h3>
        <el-table :data="availableEquipments" border>
          <el-table-column prop="equipmentCode" label="器材编号" />
          <el-table-column prop="name" label="器材名称" />
          <el-table-column prop="ageGroup" label="适配年龄段">
            <template #default="scope">
              {{ AGE_GROUP_MAP[scope.row.ageGroup as keyof typeof AGE_GROUP_MAP]?.label }}
            </template>
          </el-table-column>
          <el-table-column prop="category" label="类别" />
          <el-table-column label="资产状态" width="100">
            <template #default="scope">
              <el-tag size="small" :type="statusTagType[(scope.row as Equipment).status]">
                {{ (scope.row as Equipment).statusLabel || EQUIPMENT_STATUS_MAP[(scope.row as Equipment).status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="scope">
              <el-button size="small" type="primary" @click="handleBind((scope.row as Equipment).id)">绑定</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert
          v-if="excludedEquipments.length > 0"
          class="excluded-hint"
          type="warning"
          :closable="false"
          :title="`另有 ${excludedEquipments.length} 件器材处于送检中/已报废，已从可绑定清单隐藏，不会参与自动绑定`"
        />
      </div>

      <div class="panel">
        <h3>已绑定器材</h3>
        <el-table :data="boundEquipmentDetails" border>
          <el-table-column label="器材编号">
            <template #default="scope">{{ scope.row.equipment?.equipmentCode }}</template>
          </el-table-column>
          <el-table-column label="器材名称">
            <template #default="scope">{{ scope.row.equipment?.name }}</template>
          </el-table-column>
          <el-table-column label="资产状态" width="100">
            <template #default="scope">
              <el-tag
                v-if="scope.row.equipment"
                size="small"
                :type="statusTagType[(scope.row.equipment as Equipment).status]"
              >
                {{ (scope.row.equipment as Equipment).statusLabel || EQUIPMENT_STATUS_MAP[(scope.row.equipment as Equipment).status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="targetAgeGroup" label="绑定年龄段">
            <template #default="scope">
              {{ AGE_GROUP_MAP[scope.row.targetAgeGroup as keyof typeof AGE_GROUP_MAP]?.label }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="scope">
              <el-button size="small" type="danger" @click="handleUnbind(scope.row.equipmentId)">解绑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.session-binding {
  background: white;
  border-radius: 8px;
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.header h2 {
  margin: 0;
}

.content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.panel {
  background: #fafafa;
  border-radius: 8px;
  padding: 15px;
}

.panel h3 {
  margin: 0 0 15px 0;
  font-size: 16px;
}

.excluded-hint {
  margin-top: 12px;
}
</style>
