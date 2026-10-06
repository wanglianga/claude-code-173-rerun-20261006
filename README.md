# 城市社区无人货柜补货 / 盘点 / 临期下架服务

面向社区、办公楼、医院无人货柜的后端服务：覆盖**补货上架、三模识别售卖、温控治理、临期/过期下架、盘点差异、供应商结算、客服售后与证据回放、批次全链路追溯、点位运营**全流程。

技术栈：Java 17、Spring Boot 3.2、Spring Data JPA、PostgreSQL 16、Flyway、Kafka（KRaft）、springdoc-openapi（Swagger UI）。

---

## 1. 一键启动（Docker Compose）

依赖：Docker + Docker Compose。

```bash
docker compose up --build
```

启动后：

| 组件 | 地址 |
| --- | --- |
| 业务服务 | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| PostgreSQL | localhost:5432（库/用户/密码：cabinet/cabinet/cabinet） |
| Kafka | localhost:9092 |

容器首次启动时 Flyway 自动建表（`src/main/resources/db/migration/V1__init.sql`），并写入种子数据（测试账号、供应商、商品、批次、3 台三类点位柜机）。

---

## 2. 本地开发与验证（无需 Docker）

本机无 Docker/PostgreSQL/Kafka 时，使用内置 H2（PostgreSQL 兼容模式）+ 关闭 Kafka 监听的 `local` profile：

```bash
# 方式一：Maven
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 方式二：可执行 jar
mvn package -DskipTests
java -jar target/cabinet-service.jar --spring.profiles.active=local
```

> local profile 仅用于开发演示：设备事件走 HTTP 网关 `/api/device/events` 进入；业务事件 Kafka 发布关闭。生产/集成使用默认 profile（PostgreSQL + Kafka）。

### 自动化测试

```bash
mvn test
```

测试使用 H2 + 嵌入式 Kafka（EmbeddedKafkaBroker），**无需任何外部中间件**。

### 一键场景演示

服务启动后执行（依赖 curl/python3）：

```bash
scripts/demo.sh
# 自定义地址: BASE_URL=http://host:8080 scripts/demo.sh
```

脚本顺序演示 12 个场景：补货登记与照片 → 放错货道拦截/纠正 → 三模一致售卖 → 拿起放回不计费 → 客服证据回放 → 温度异常告警/全柜隔离/复核解除 → 临期扫描下架报损 → 盘点差异调整 → 运营点位指标与品类调整 → 投诉误扣款全额退款回库 → 供应商结算确认 → 批次追溯 → 设备网关直报。

---

## 3. 测试账号（HTTP 头 `X-Staff-Token`）

| 角色 | 令牌 | 主要权限 |
| --- | --- | --- |
| 超级管理员 | `root-token` | 全部 |
| 运营 | `operator-token` | 点位指标、品类调整、告警、人工复核、主数据写入 |
| 财务 | `finance-token` | 供应商结算 |
| 客服 | `cs-token` | 投诉、退款、证据回放 |
| 补货员 | `replenisher-token` | 补货、盘点、温控处置、下架、卡货 |
| 供应商（鲜食达） | `supplier-token` | 本供应商批次追溯、结算单 |

设备网关鉴权使用请求头 `X-Device-Key: device-secret-2026`（可用环境变量 `DEVICE_KEY` 覆盖）。

Swagger UI 右上角点 **Authorize**，输入上述任一令牌即可在线调试。

种子柜机：`CB-SQ-001`（阳光花园社区）、`CB-OF-001`（办公楼）、`CB-HP-001`（医院）；
种子批次：`B-MILK-001`（鲜奶）、`B-RICE-NEAR`（2 天后到期的临期饭团）、`B-RICE-EXPIRED`（已过期饭团）、`B-WATER-001`、`B-DUMP-001`。

---

## 4. 核心设计

### 4.1 三模识别对账（重量 / 视觉 / RFID）

`RecognitionReconciler`（`service/recon`，纯逻辑可单测）以一次开门会话内全部原始事件为输入：

1. **重量是物理数量锚**：拿起（减重）与后续放回（增重）在 ±25% 容差内配对，配上的整组不计费；
2. **视觉/RFID 投票指认 SKU**：低置信度（<0.55）视觉事件丢弃；同货道同 SKU 的拿/放配对；
3. **货道仲裁**：重量净减与投票数量一致且 SKU 在架 → 确定售卖，按 **FEFO（先到期先出）** 指定批次；重量与识别冲突、货道多 SKU 无法指认、单模态无重量锚定等情况 → `UNCERTAIN`，**不自动扣款，转人工复核**并产生告警；
4. 每条原始事件都落库对账结论（PICK/RETURN/UNCERTAIN/IGNORED），可逐条回放。

### 4.2 库存台账与批次状态机

- `batch_inventory`：批次×货道的在架/隔离/临期/下架暂存/已售/报损数量聚合；
- `inventory_ledger`：**每次**库存变动一条流水（补货/售卖/冲销/放回/卡货取回/错货道移货/临期标记/下架/温控隔离/解除/报损/盘点调整），含原因码、关联单据、操作人与变更后余额，支持按时间窗回放；
- 批次状态：`IN_WAREHOUSE → ON_SHELF / NEAR_EXPIRY / QUARANTINED → REMOVED / SCRAPPED → SETTLED`。

### 4.3 食品安全联动

- **温控**：温度采样按柜机温层（冷藏 0–8℃、冷冻 ≤-15℃、常温 ≤30℃）判定；异常→温度告警+柜机置 `TEMP_ALARM`；可一键**全柜隔离**（在架→不可售隔离），复核合格解除回架，变质则隔离报损（`TEMP_SPOIL`）；
- **临期/过期**：定时扫描（默认每 10 分钟，`app.expiry.scan-cron`，阈值 `app.expiry.near-expiry-days`），临期生成下架任务并标记，过期为 CRITICAL 且**禁止复核回架**；补货时已过期批次禁止上架；下架任务可处置为下架暂存（退仓）/复核回架（仅临期）/报损。

### 4.4 盘点、卡货、错放货道

- 盘点按柜机拉系统库存快照，录入实盘数与差异原因（卡货/识别偏差/错放货道/原因不明/初盘错误），确认后写 `STOCKTAKE_ADJUST` 流水并对盘亏告警；
- 卡货事件可处置为取回上架（已计费则冲销售卖回库）/损坏报损/误报忽略；
- 补货录入校验**货道温层与商品温度要求**、**货道 expectedSku 一致性**；不符标记 `WRONG_CHANNEL` 并 CRITICAL 告警，确认上架被阻断，必须纠正移货（产生跨货道流水）或显式接受。

### 4.5 售后证据链与误扣款

关门结算时归档：开门/关门记录、三模识别事件及对账结论、视频片段（URL+起止毫秒）、会话时间窗内的库存流水。客服按会话号一次取齐。退款支持全额/部分，可选择回库冲销（已售→在架，`SALE_REVERSED` 流水），订单→`REFUNDED/PARTIALLY_REFUNDED`，投诉工单联动关闭。

### 4.6 供应商结算与批次追溯

- 财务按供应商+账期生成结算单：`应付款 = 真实售卖成本 − 报损成本`（过期/温控变质损耗由供应商承担），明细按批次列出；确认后报损置 settled、批次置 SETTLED；
- `GET /api/trace/batches/{batchNo}` 与供应商门户返回批次的补货、售卖订单、下架、报损（含损耗原因）、台账时间线与数量汇总。

### 4.7 Kafka 与设备接入

- Topic `cabinet.device.events`：柜机上报（`DOOR_OPEN / RECOGNITION / DOOR_CLOSE / TEMPERATURE / JAM`），`DeviceEventIngestService` 以 `eventId` **幂等去重**并归档 `event_log`，HTTP 网关 `/api/device/events` 与 Kafka 消费共用同一入口；
- Topic `cabinet.biz.events`：服务发布领域事件（开门、订单结算、退款、补货确认、温度告警、临期、报损、结算等）。

---

## 5. 主要接口（共 49 个路径，详见 Swagger）

| 模块 | 代表接口 |
| --- | --- |
| 主数据 | `POST /api/master/{suppliers,products,batches,cabinets,cabinets/{code}/channels}` |
| 补货 | `POST /api/replenishments/arrive`、`/{id}/items`、`/{id}/confirm`、`/items/{id}/correct` |
| 设备网关 | `POST /api/device/events`（`X-Device-Key`） |
| 购物会话 | `POST /api/sessions/open`、`/{id}/events`、`/{sessionNo}/close`、`GET /{sessionNo}/replay` |
| 人工复核 | `GET /api/orders/review/pending`、`POST /api/orders/{id}/review` |
| 温控 | `POST /api/temperature/record`、`/alarms/{id}/quarantine`、`/alarms/{id}/release` |
| 临期下架 | `POST /api/removals/scan`、`/{id}/handle` |
| 盘点 | `POST /api/stocktakes/create`、`/items/{id}/count`、`/{id}/confirm` |
| 卡货 | `POST /api/jams/report`、`/{id}/resolve` |
| 运营 | `GET /api/operations/metrics`、`POST /api/operations/category-adjustments`、`/api/operations/alerts` |
| 客服 | `POST /api/cs/complaints`、`/{id}/refund`、`/{id}/no-fault`、`GET /api/cs/sessions/{no}/replay` |
| 财务 | `POST /api/finance/settlements/generate`、`/{id}/confirm` |
| 追溯/供应商 | `GET /api/trace/batches/{batchNo}`、`/api/supplier/batches/{batchNo}/trace`、`/api/supplier/settlements` |

---

## 6. 需求场景覆盖对照

| 题目要求 | 实现 |
| --- | --- |
| 记录柜机编号/批次/保质期/数量/货道/温度要求/补货照片 | 补货单+明细，`replenishment.photo_url`，批次生产日期/保质期，货道温层校验 |
| 重量/视觉/RFID 识别拿取并生成订单 | 三模对账引擎 + FEFO 批次 + 订单/明细 |
| 识别错误、拿起又放回 | 重量/识别双重配对；置信度阈值；冲突→UNCERTAIN 人工复核 |
| 货道卡货 | 卡货事件与处置（取回回库/报损/忽略），已计费自动冲销 |
| 冷藏温度异常 | 采样判定、告警、柜机状态、全柜隔离、复核解除/变质报损 |
| 临期未及时下架 | 定时扫描生成下架任务+告警，过期 CRITICAL、禁回架、可报损 |
| 补货员放错货道 | 温层/SKU 校验、WRONG_CHANNEL 阻断、纠正移货流水 |
| 盘点与系统不符 | 盘点快照、差异归因、STOCKTAKE_ADJUST 流水、盘亏告警 |
| 用户被误扣款 | 投诉工单、证据回放、全/部分退款、回库冲销 |
| 按点位调整品类 | 品类调整单（立即生效或下次补货生效） |
| 批次追踪补货/售卖/下架/报损/结算 | 批次追溯视图 + 台账时间线 + 批次 SETTLED 状态 |
| 客服看开门记录/识别结果/视频/库存变化 | 会话证据回放接口 |
| 按点位销量/损耗/临期率安排补货 | 运营指标接口（分 SKU 销量、损耗率、低库存、汰换/增排面建议） |
| 财务按真实售卖和报损结算供应商 | 账期结算单（售卖成本−报损成本），按批次明细 |
| 供应商按批次看损耗原因 | 供应商门户（仅本供应商数据），报损记录含 EXPIRED/TEMP_SPOIL/JAM_DAMAGE 原因 |
| 证据不足/可回放 | 原始事件+对账结论+视频+库存流水四件套；UNCERTAIN 不扣款转人工 |

---

## 7. 本次实际执行过的验证

- `mvn test`：**20 个测试全部通过**（8 个对账引擎单元测试 + 10 个全链路集成测试 + 2 个 Kafka 端到端/HTTP 鉴权集成测试），H2 + 嵌入式 Kafka；
- `scripts/demo.sh`（local profile 实跑）：12 个场景全部成功，零错误响应；关键结果实测：
  - 三模一致售卖 CONFIRMED 6.50；拿起放回订单金额 0；
  - 放错货道返回 `WRONG_CHANNEL`，纠正后才能确认上架；
  - 12℃ 采样返回 HIGH，温度告警→全柜隔离（`actionTaken=QUARANTINE`）→复核解除回架；
  - 临期饭团扫描生成下架任务并报损 SCRAPPED；
  - 盘点差异 `ADJUSTED`；误扣款退款 DONE、订单 REFUNDED 并回库；
  - 结算单售卖成本 3.00、报损成本 20.00、应付 -17.00，状态 CONFIRMED；批次状态 SETTLED；
  - 设备网关事件返回 `{"result":"PROCESSED"}`；
- Swagger UI `/swagger-ui/index.html` 返回 200，OpenAPI 含 49 个接口路径。

## 8. 目录结构

```
src/main/java/com/community/cabinet/
├── api/            REST 控制器(按角色鉴权)
├── common/         常量/异常/鉴权拦截器/OpenAPI/单号
├── domain/entity   JPA 实体(26 张表)
├── domain/repository
├── kafka/          Topic/生产者/设备事件消费器/幂等归档
├── service/        补货/会话对账/温控/临期/盘点/卡货/报损/结算/投诉/追溯/运营
├── service/recon/  三模识别对账引擎(纯逻辑)
└── bootstrap/      种子数据
src/test/           单元测试 + H2/EmbeddedKafka 集成测试
scripts/demo.sh     12 场景一键演示
docker-compose.yml  PostgreSQL + Kafka(KRaft) + app
Dockerfile          多阶段构建
```
