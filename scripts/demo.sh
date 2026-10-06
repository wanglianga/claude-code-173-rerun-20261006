#!/usr/bin/env bash
# 无人货柜服务一键场景演示。
# 前置: 服务已启动(默认 http://localhost:8080), 种子数据已初始化。
# 依赖: curl, python3。
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
J() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval(\"d$1\"))"; }

ROOT=(-H "X-Staff-Token: root-token")
OP=(-H "X-Staff-Token: operator-token")
FIN=(-H "X-Staff-Token: finance-token")
CS=(-H "X-Staff-Token: cs-token")
RP=(-H "X-Staff-Token: replenisher-token")
SUP=(-H "X-Staff-Token: supplier-token")
DEV=(-H "X-Device-Key: device-secret-2026" -H "Content-Type: application/json")
JSON=(-H "Content-Type: application/json")

echo "== 0. 鉴权 =="
curl -s "$BASE/api/auth/me" "${ROOT[@]}" | python3 -m json.tool

echo "== 1. 补货: 到货(阳光花园社区柜 CB-SQ-001), 含补货照片 =="
RID=$(curl -s -X POST "$BASE/api/replenishments/arrive" "${RP[@]}" "${JSON[@]}" \
  -d '{"cabinetCode":"CB-SQ-001","photoUrl":"https://example.com/photos/rp1.jpg","note":"早班补货"}' | J "['id']")
echo "补货单 id=$RID"
curl -s -X POST "$BASE/api/replenishments/$RID/items" "${RP[@]}" "${JSON[@]}" \
  -d '{"batchNo":"B-MILK-001","channelCode":"A1","qty":8}' | python3 -m json.tool

echo "== 1b. 把临期饭团放进牛奶货道 A1 -> 系统标记 WRONG_CHANNEL 并告警 =="
WRONG=$(curl -s -X POST "$BASE/api/replenishments/$RID/items" "${RP[@]}" "${JSON[@]}" \
  -d '{"batchNo":"B-RICE-NEAR","channelCode":"A1","qty":5}')
echo "$WRONG" | python3 -m json.tool
WID=$(echo "$WRONG" | J "['id']")
echo "== 1c. 纠正到 A2, 然后确认上架 =="
curl -s -X POST "$BASE/api/replenishments/items/$WID/correct" "${RP[@]}" "${JSON[@]}" \
  -d '{"channelCode":"A2"}' | python3 -m json.tool
curl -s -X POST "$BASE/api/replenishments/$RID/confirm" "${RP[@]}" "${JSON[@]}" -d '{}' | python3 -m json.tool

echo "== 2. 用户购物: 开门 -> 重量/视觉/RFID 三模一致 -> 关门结算 =="
RESP=$(curl -s -X POST "$BASE/api/sessions/open" "${ROOT[@]}" "${JSON[@]}" \
  -d '{"cabinetCode":"CB-SQ-001","userId":"U-DEMO-1","videoClipUrl":"https://example.com/v/1"}')
SID=$(echo "$RESP" | J "['id']"); SNO=$(echo "$RESP" | J "['sessionNo']")
curl -s -X POST "$BASE/api/sessions/$SID/events" "${ROOT[@]}" "${JSON[@]}" \
  -d '{"modality":"WEIGHT","action":"PICK","channelCode":"A1","weightDeltaG":-250,"seq":1}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SID/events" "${ROOT[@]}" "${JSON[@]}" \
  -d '{"modality":"VISION","action":"PICK","channelCode":"A1","sku":"SKU-MILK","confidence":0.96,"seq":2}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SID/events" "${ROOT[@]}" "${JSON[@]}" \
  -d '{"modality":"RFID","action":"PICK","channelCode":"A1","sku":"SKU-MILK","seq":3}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SNO/close" "${ROOT[@]}" "${JSON[@]}" -d '{}' | python3 -m json.tool

echo "== 3. 拿起又放回: 重量与视觉双双配对, 金额 0 =="
RESP2=$(curl -s -X POST "$BASE/api/sessions/open" "${ROOT[@]}" "${JSON[@]}" \
  -d '{"cabinetCode":"CB-SQ-001","userId":"U-DEMO-2"}')
SID2=$(echo "$RESP2" | J "['id']"); SNO2=$(echo "$RESP2" | J "['sessionNo']")
curl -s -X POST "$BASE/api/sessions/$SID2/events" "${ROOT[@]}" "${JSON[@]}" -d '{"modality":"WEIGHT","action":"PICK","channelCode":"A1","weightDeltaG":-250,"seq":1}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SID2/events" "${ROOT[@]}" "${JSON[@]}" -d '{"modality":"VISION","action":"PICK","channelCode":"A1","sku":"SKU-MILK","confidence":0.9,"seq":2}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SID2/events" "${ROOT[@]}" "${JSON[@]}" -d '{"modality":"VISION","action":"RETURN","channelCode":"A1","sku":"SKU-MILK","confidence":0.9,"seq":3}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SID2/events" "${ROOT[@]}" "${JSON[@]}" -d '{"modality":"WEIGHT","action":"RETURN","channelCode":"A1","weightDeltaG":252,"seq":4}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SNO2/close" "${ROOT[@]}" "${JSON[@]}" -d '{}' | python3 -m json.tool

echo "== 4. 证据回放(开门/识别结论/视频片段/库存变化) =="
curl -s "$BASE/api/cs/sessions/$SNO/replay" "${CS[@]}" | python3 -m json.tool

echo "== 5. 冷藏柜温度异常 12C -> 告警 -> 全柜隔离 =="
curl -s -X POST "$BASE/api/temperature/record" "${RP[@]}" "${JSON[@]}" \
  -d '{"cabinetCode":"CB-SQ-001","tempC":12.0,"sensor":"S1"}' | python3 -m json.tool
# 取温度告警主键(运营告警的 refId 指向 temperature_alarm.id)
ALARM=$(curl -s "$BASE/api/operations/alerts?status=OPEN" "${OP[@]}" \
  | python3 -c "import sys,json;print(next(a['refId'] for a in json.load(sys.stdin) if a['alertType']=='TEMP_ABNORMAL'))")
curl -s -X POST "$BASE/api/temperature/alarms/$ALARM/quarantine" "${RP[@]}" | python3 -m json.tool

echo "== 5b. 复核合格: 牛奶解除隔离回架(饭团临期, 保留隔离待步骤6下架) =="
python3 - "$BASE" <<'PY'
import json, sys, urllib.request
base = sys.argv[1]
def call(method, path, token, body=None):
    req = urllib.request.Request(base + path, method=method,
        headers={"X-Staff-Token": token, "Content-Type": "application/json"},
        data=json.dumps(body).encode() if body else None)
    with urllib.request.urlopen(req) as resp:
        raw = resp.read().decode()
    return json.loads(raw) if raw.strip() else {"ok": True}
chans = {c["id"]: c["code"] for c in call("GET", "/api/master/cabinets/CB-SQ-001/channels", "replenisher-token")}
trace = call("GET", "/api/trace/batches/B-MILK-001", "supplier-token")
alarms = call("GET", "/api/operations/alerts?status=OPEN", "operator-token")
# 取最近一条已处置温度告警(隔离后告警状态为 DISPOSED, 从全部告警中找)
all_alerts = call("GET", "/api/operations/alerts?status=CLOSED", "operator-token")
for st in ("OPEN", "ACK", "CLOSED"):
    cand = [a for a in call("GET", f"/api/operations/alerts?status={st}", "operator-token")
            if a["alertType"] == "TEMP_ABNORMAL" and a.get("refId")]
    if cand:
        alarm_id = cand[0]["refId"]; break
for inv in trace["inventoryByChannel"]:
    if chans.get(inv["channelId"]) == "A1" and inv["quarantinedQty"] > 0:
        r = call("POST", f"/api/temperature/alarms/{alarm_id}/release", "replenisher-token",
                 {"batchId": inv["batchId"], "channelId": inv["channelId"],
                  "qty": inv["quarantinedQty"], "note": "复核合格回架"})
        print("解除隔离:", json.dumps(r, ensure_ascii=False))
PY

echo "== 6. 临期扫描: B-RICE-NEAR 2 天后到期 -> 下架任务; 处置为报损 =="
curl -s -X POST "$BASE/api/removals/scan" "${RP[@]}" | python3 -m json.tool
RMID=$(curl -s "$BASE/api/removals/pending" "${RP[@]}" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")
curl -s -X POST "$BASE/api/removals/$RMID/handle" "${RP[@]}" "${JSON[@]}" \
  -d '{"action":"SCRAPPED","note":"临期下架销毁"}' | python3 -m json.tool

echo "== 7. 盘点账实不符调整 =="
STID=$(curl -s -X POST "$BASE/api/stocktakes/create" "${RP[@]}" "${JSON[@]}" \
  -d '{"cabinetCode":"CB-SQ-001","note":"月盘"}' | J "['id']")
ITEM=$(curl -s "$BASE/api/stocktakes/$STID" "${RP[@]}" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['items'][0]['item']['id'])")
SYSQ=$(curl -s "$BASE/api/stocktakes/$STID" "${RP[@]}" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['items'][0]['item']['systemQty'])")
curl -s -X POST "$BASE/api/stocktakes/items/$ITEM/count" "${RP[@]}" "${JSON[@]}" \
  -d "{\"countedQty\":$((SYSQ-1)),\"reason\":\"UNKNOWN_LOSS\",\"note\":\"盘亏1件\"}" | python3 -m json.tool
curl -s -X POST "$BASE/api/stocktakes/$STID/confirm" "${RP[@]}" | python3 -m json.tool

echo "== 8. 运营点位指标(销量/损耗/临期)与品类调整 =="
curl -s "$BASE/api/operations/metrics?cabinetCode=CB-SQ-001&days=7" "${OP[@]}" | python3 -m json.tool
curl -s -X POST "$BASE/api/operations/category-adjustments" "${OP[@]}" "${JSON[@]}" \
  -d '{"cabinetCode":"CB-SQ-001","channelCode":"A2","newExpectedSku":"SKU-MILK","reason":"饭团损耗高, 改卖牛奶","applyNow":true}' | python3 -m json.tool

echo "== 9. 客服投诉误扣款并退款(回库冲销): 先产生一笔被误扣订单 =="
RESP9=$(curl -s -X POST "$BASE/api/sessions/open" "${ROOT[@]}" "${JSON[@]}" \
  -d '{"cabinetCode":"CB-SQ-001","userId":"U-DEMO-9","videoClipUrl":"https://example.com/v/9"}')
SID9=$(echo "$RESP9" | J "['id']"); SNO9=$(echo "$RESP9" | J "['sessionNo']")
curl -s -X POST "$BASE/api/sessions/$SID9/events" "${ROOT[@]}" "${JSON[@]}" -d '{"modality":"WEIGHT","action":"PICK","channelCode":"A1","weightDeltaG":-250,"seq":1}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SID9/events" "${ROOT[@]}" "${JSON[@]}" -d '{"modality":"VISION","action":"PICK","channelCode":"A1","sku":"SKU-MILK","confidence":0.95,"seq":2}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SID9/events" "${ROOT[@]}" "${JSON[@]}" -d '{"modality":"RFID","action":"PICK","channelCode":"A1","sku":"SKU-MILK","seq":3}' >/dev/null
curl -s -X POST "$BASE/api/sessions/$SNO9/close" "${ROOT[@]}" "${JSON[@]}" -d '{}' | python3 -m json.tool
OID=$(curl -s "$BASE/api/sessions/$SNO9/replay" "${CS[@]}" | J "['order']['id']")
OIID=$(curl -s "$BASE/api/sessions/$SNO9/replay" "${CS[@]}" | python3 -c "import sys,json;print(json.load(sys.stdin)['orderItems'][0]['id'])")
CID=$(curl -s -X POST "$BASE/api/cs/complaints" "${CS[@]}" "${JSON[@]}" \
  -d "{\"userId\":\"U-DEMO-9\",\"sessionNo\":\"$SNO9\",\"reason\":\"没拿商品被扣款\"}" | J "['id']")
curl -s -X POST "$BASE/api/cs/complaints/$CID/refund" "${CS[@]}" "${JSON[@]}" \
  -d "{\"orderId\":$OID,\"amount\":6.50,\"reason\":\"MISCHARGE\",\"detail\":\"识别错误全额退\",\"restockItemIds\":[$OIID]}" | python3 -m json.tool

echo "== 10. 财务按真实售卖与报损生成并确认结算单(供应商1 鲜食达) =="
TODAY=$(date +%F)
STL=$(curl -s -X POST "$BASE/api/finance/settlements/generate" "${FIN[@]}" "${JSON[@]}" \
  -d "{\"supplierId\":1,\"periodStart\":\"2020-01-01\",\"periodEnd\":\"$TODAY\"}")
echo "$STL" | python3 -m json.tool
STLID=$(echo "$STL" | J "['id']")
curl -s -X POST "$BASE/api/finance/settlements/$STLID/confirm" "${FIN[@]}" "${JSON[@]}" \
  -d '{"note":"账期确认"}' | python3 -m json.tool

echo "== 11. 供应商按批次追溯(补货/售卖/下架/报损/结算+损耗原因) =="
curl -s "$BASE/api/supplier/batches/B-MILK-001/trace" "${SUP[@]}" | python3 -m json.tool

echo "== 12. 设备网关 HTTP 直报(等价 Kafka 主题 cabinet.device.events), X-Device-Key 鉴权 =="
curl -s -X POST "$BASE/api/device/events" "${DEV[@]}" \
  -d '{"eventId":"evt-demo-temp-1","eventType":"TEMPERATURE","cabinetCode":"CB-OF-001","data":{"tempC":4.5,"sensor":"S1"}}'
echo
echo "全部场景执行完成。Swagger: $BASE/swagger-ui.html"
