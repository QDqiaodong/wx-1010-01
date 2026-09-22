<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElTableColumn, ElButton } from 'element-plus'
import type { Equipment, AgeGroup, EquipmentStatus } from '@/types'
import { AGE_GROUP_MAP, EQUIPMENT_STATUS_MAP } from '@/types'
import { equipmentApi, apiError } from '@/api'
import AgeGroupFilter from '@/components/AgeGroupFilter.vue'
import EquipmentForm from '@/components/EquipmentForm.vue'

const router = useRouter()
const equipments = ref<Equipment[]>([])
const filterAgeGroup = ref<AgeGroup | ''>('')
const showForm = ref(false)
const editingEquipment = ref<Equipment | null>(null)

const loadEquipments = async () => {
  equipments.value = await equipmentApi.getAll(filterAgeGroup.value || undefined)
}

onMounted(loadEquipments)

const handleFilterChange = (value: AgeGroup | '' | AgeGroup[]) => {
  filterAgeGroup.value = typeof value === 'string' ? value : value[0] || ''
  loadEquipments()
}

const handleAdd = () => {
  editingEquipment.value = null
  showForm.value = true
}

const handleEdit = (equipment: Equipment) => {
  editingEquipment.value = equipment
  showForm.value = true
}

const handleDelete = async (id: number) => {
  try {
    await equipmentApi.delete(id)
    ElMessage.success('删除成功')
    loadEquipments()
  } catch (e: any) {
    ElMessage({
      type: e?.response?.status === 409 ? 'warning' : 'error',
      message: apiError(e, '删除失败'),
      duration: 5000
    })
  }
}

const handleSubmit = async (data: Omit<Equipment, 'id'>) => {
  try {
    if (editingEquipment.value) {
      await equipmentApi.update(editingEquipment.value.id, data)
      ElMessage.success('更新成功')
    } else {
      await equipmentApi.create(data)
      ElMessage.success('创建成功')
    }
    showForm.value = false
    loadEquipments()
  } catch (e: any) {
    ElMessage({
      type: e?.response?.status === 409 ? 'warning' : 'error',
      message: apiError(e, '保存失败'),
      duration: 6000
    })
  }
}

const handleCancel = () => {
  showForm.value = false
}

const statusTagType: Record<EquipmentStatus, 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
  AVAILABLE: 'success',
  IN_USE: 'warning',
  MAINTENANCE: 'info',
  INSPECTION: 'danger',
  SCRAPPED: 'info'
}

const goInspection = (equipment: Equipment) => {
  // 跳到送检台并带上 equipmentId，送检台自动打开已预选该器材的送检弹窗
  router.push({ path: '/inspection', query: { equipmentId: String(equipment.id) } })
}
</script>

<template>
  <div class="equipment-management">
    <div class="header">
      <h2>低温游乐器材管理</h2>
      <div class="actions">
        <AgeGroupFilter v-model="filterAgeGroup" @update:modelValue="handleFilterChange" />
        <el-button type="primary" @click="handleAdd">新增器材</el-button>
      </div>
    </div>

    <el-table :data="equipments" border>
      <el-table-column prop="equipmentCode" label="器材编号" />
      <el-table-column prop="name" label="器材名称" />
      <el-table-column prop="frostResistanceSpec" label="耐寒规格" />
      <el-table-column prop="ageGroup" label="适配年龄段">
        <template #default="scope">
          <span>{{ AGE_GROUP_MAP[scope.row.ageGroup as keyof typeof AGE_GROUP_MAP]?.label }} ({{ AGE_GROUP_MAP[scope.row.ageGroup as keyof typeof AGE_GROUP_MAP]?.ageRange }})</span>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="器材类别" />
      <el-table-column prop="status" label="状态" width="110">
        <template #default="scope">
          <el-tag :type="statusTagType[scope.row.status as EquipmentStatus]">
            {{ scope.row.statusLabel || EQUIPMENT_STATUS_MAP[scope.row.status as keyof typeof EQUIPMENT_STATUS_MAP] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280">
        <template #default="scope">
          <el-button size="small" @click="handleEdit(scope.row as Equipment)">编辑</el-button>
          <el-button
            size="small"
            type="warning"
            :disabled="(scope.row as Equipment).status === 'SCRAPPED' || !!(scope.row as Equipment).openInspectionId"
            @click="goInspection(scope.row as Equipment)"
          >
            {{ (scope.row as Equipment).openInspectionId ? `送检中 #${(scope.row as Equipment).openInspectionId}` : '送检' }}
          </el-button>
          <el-button
            size="small"
            type="danger"
            :disabled="(scope.row as Equipment).status === 'SCRAPPED'"
            @click="handleDelete((scope.row as Equipment).id)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert
      class="hint"
      type="info"
      :closable="false"
      title="「送检」会在器材送检台创建送检单；送检中的器材不能绑定场次/发装，复检通过后自动回可用池，报废器材保留历史但永久退出可绑定清单。点击顶部菜单「器材送检台」可处理送检单并查看完整痕迹。"
    />

    <EquipmentForm :equipment="editingEquipment" :visible="showForm" @submit="handleSubmit" @cancel="handleCancel" />
  </div>
</template>

<style scoped>
.equipment-management {
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

.actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.hint {
  margin-top: 16px;
}
</style>
