# wx-1010 冰雪乐园装备与场次绑定系统

## 项目简介

冰雪乐园装备与场次绑定系统，包含 Spring Boot 后端、Vue/Vite 前端、MySQL 和 Redis。项目已统一为 UTF-8 编码，并通过 Docker Compose 固定端口交付。

## 端口

- 前端: http://localhost:3210 / http://127.0.0.1:3210
- 后端 API: http://localhost:3310/api
- MySQL: 127.0.0.1:3410
- Redis: 127.0.0.1:6510

## 构建与启动

```bash
cd /Users/Admin/Desktop/solo-0601/wx-0701/wx-组1/wx-1010
cd backend && mvn compile -q
cd ../frontend && npm ci && npm run build
cd .. && docker compose up -d --build
```

也可以执行：

```bash
./start.sh
```

## Docker 构建缓存

- 后端 Dockerfile 先复制 `pom.xml` 和 `settings.xml` 并下载 Maven 依赖，再复制 `src` 编译。
- 前端 Dockerfile 先复制 `package.json` 和 `package-lock.json` 并安装 npm 依赖，再复制源码执行构建。
- `.dockerignore` 排除了 `node_modules`、`dist`、`target`、日志、临时文件、截图和 IDE 配置。

## 入场发装台（现场发装/归还闭环）

独立操作页：前端菜单「入场发装台」（路由 `/dispatch`，支持 `?sessionId=` 预选场次），也可在「场次管理」行内点「入场发装」进入。

覆盖「发出去—用着—还回来」完整链路，并发与状态约束全部在后端/数据库保证：

1. **同一器材同一时刻只能发给一位游客**：发装先对场次行加悲观写锁串行化同场次操作，再对绑定关系做条件 UPDATE（CAS，仅"在架"可置"已领用"，影响行数 0 即失败），流水表 `outstanding_key` 唯一索引做跨场次兜底。并发领用只有一人成功，其他人收到 HTTP 409「已被其他工作人员领用」。
2. **年龄段 + 气温双校验**：游客年龄段须与器材在本场次的目标年龄段匹配，实测气温不得低于从抗冻规格文本解析出的下限；两条同时校验，任一不满足都会在错误信息中具体指出（两条同时不满足会分别列出）。实测气温等于下限（临界值）允许发装。
3. **场次时序与结束兜底**：只有"进行中"场次可发装；场次结束（专用接口或编辑改状态）在同一事务内把所有"已领用未归还"流水置为"结束兜底收回"、绑定状态复位在架、器材资产状态复位可用，不会出现场次结束后器材悬空、无法归还或无法再绑定的情况。
4. **留痕**：`equipment_dispatch_record` 记录每次发装的器材、游客、游客年龄段、实测气温、抗冻下限、发装人、发装时间，以及归还/兜底的操作人与时间；可按场次查询完整流水。归还后器材立即可重新领用。

接口（均在 `/api/session/{sessionId}/dispatch` 下）：

- `GET /items`：现场视图（绑定器材 + 当前发装状态 + 当前使用游客）
- `GET /records`：按场次查发装/归还流水
- `POST /issue`：发装（请求体含 equipmentId、visitorName、visitorAgeGroup、temperature、operator）
- `POST /records/{recordId}/return`：归还（请求体含 operator）
- 场次状态：`POST /api/session/{id}/start`、`POST /api/session/{id}/end`

后端测试：`cd backend && mvn test`（含 8 线程并发领用、并发归还、临界气温、双校验、场次结束兜底、兜底后新场次重新领用等 17 个用例，使用 H2 MySQL 模式验证真实数据库锁与唯一约束行为）。

## 器材送检台（送检 → 维修 → 复检 → 放行/报废闭环）

独立操作页：前端菜单「器材送检台」（路由 `/inspection`，器材列表行内「送检」按钮可带 `?equipmentId=` 预选器材）。

把现场发现异常的器材送去维修、复检并重新放回可用池，状态流与留痕全部在后端/数据库保证：

1. **送检单状态机**：`SUBMITTED 待维修` →（维修退回补充材料）`MATERIAL_NEEDED 待补充材料` →（补齐）`SUBMITTED` →（维修完成提交复检）`REINSPECTING 复检中` → `CLOSED_PASSED 复检通过关闭`；复检不通过退回 `SUBMITTED` 可反复维修复检；任意待维修/待补料/复检中环节均可 `CLOSED_SCRAPPED 报废关闭`。终态单据不可再改，再次送检必须新建单据。
2. **重复送检互斥**：`inspection_order.open_key` 未关闭期间写 `"inspection:{equipmentId}"` 并配唯一索引，同一件器材任何时刻至多一张未关闭送检单；应用层先查给出带单号的中文 409 提示，唯一索引兜底并发（含 6 线程并发送检测试）。
3. **历史不覆盖**：每次操作（提交/退补/补齐/提交复检/复检通过/不通过/报废）向 `inspection_event` 追加一条痕迹（操作人、说明、前后状态、时间），后一次复检不覆盖此前维修与退回记录，详情页按时间线完整回溯。
4. **已发给游客的器材**：送检时可先按当前流水正常归还；无法归还时勾选「转入待处理」，流水置为 `TRANSFERRED_PENDING 转入待处理`（记录发现人）、绑定行隔离，再进入维修——不会留下既不能归还又不能维修的悬空记录。转入待处理后不能再重复归还。
5. **送检期间不可绑定/发装**：器材资产置为 `INSPECTION 送检中`，既有场次绑定行置为 `QUARANTINED 送检隔离`；手动/自动绑定、现场发装全部由后端拒绝（409 明确提示刷新），旧页面停留期间提交也不会覆盖新状态，原有发装流水保留。
6. **复检通过重新放行**：隔离绑定复位在架（进行中场次立即可再发装），器材无绑定时回 `AVAILABLE 可用`、仍绑定时回 `IN_USE 使用中`；场次结束兜底不会把送检中器材错误复位为可用。
7. **报废**：资产置 `SCRAPPED 已报废` 且必须填报废理由，绑定行转为 `SCRAPPED 已报废留档`（不删除），从可绑定/可发装清单永久消失；有送检历史的器材禁止物理删除。
8. **加锁顺序**：送检与发装/归还/结束场次统一按「场次行（多场次按ID升序）→ 绑定行 → 器材行」加悲观写锁，附 20 轮「发装 vs 送检」竞争测试验证无死锁、无锁超时。

接口（`/api/inspection`）：

- `POST /api/inspection`：提交送检（equipmentId、reporter、problemDescription、forceTransfer）
- `POST /api/inspection/{id}/return-materials` / `/resubmit` / `/reinspect`
- `POST /api/inspection/{id}/reinspect/pass` / `/reinspect/fail`
- `POST /api/inspection/{id}/scrap`（报废理由必填）
- `GET /api/inspection?open=true|false` / `GET /api/inspection/{id}`（含操作痕迹）/ `GET /api/inspection/equipment/{equipmentId}`

发装流水新增状态 `TRANSFERRED_PENDING 转入待处理`；器材资产状态新增 `INSPECTION 送检中`、`SCRAPPED 已报废`；绑定发装状态新增 `QUARANTINED 送检隔离`、`SCRAPPED 已报废留档`。

后端测试共 34 个用例（`mvn test`），其中送检链路 17 个：重复送检、并发送检、转入待处理、送检期间归还、场次结束不复活、退补材料、复检不通过、复检通过再发装/再送检、报废不可绑定发装、发装vs送检无死锁等，均用 H2 MySQL 模式验证真实约束。

