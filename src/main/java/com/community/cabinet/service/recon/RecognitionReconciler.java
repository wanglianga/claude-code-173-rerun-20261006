package com.community.cabinet.service.recon;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 三模识别对账引擎(纯逻辑, 无框架依赖, 便于单测)。
 *
 * 输入一次开门会话内重量/视觉/RFID 上报的全部原始事件 + 各货道当前批次库存,
 * 输出净拿取明细与每条原始事件的对账结论。
 *
 * 规则:
 * 1. 重量: 按时间把"拿起(减重)"与后续"放回(增重)"在 ±25% 容差内配对, 配上的不计费;
 * 2. 视觉/RFID: 同货道同 SKU 的拿/放配对, 低置信度视觉事件丢弃;
 * 3. 货道级仲裁: 重量是"物理数量锚", 视觉/RFID 负责 SKU 投票;
 *    - 重量净减 = 投票数量且 SKU 在架 -> 确定;
 *    - 重量有变化但视觉/RFID 缺失或冲突, 或货道多 SKU 无法指认 -> UNCERTAIN(证据不足, 转人工, 不自动扣款);
 *    - 重量无变化但视觉/RFID 报告拿取 -> 疑似误识别, UNCERTAIN;
 * 4. 确定行按 FEFO(先到期先出) 指定批次, SKU 无法指认时 batchId=null。
 */
@Component
public class RecognitionReconciler {

    public static final BigDecimal MIN_VISION_CONFIDENCE = new BigDecimal("0.55");
    private static final BigDecimal LOW = new BigDecimal("0.80");
    private static final BigDecimal HIGH = new BigDecimal("1.25");

    public ReconcileResult reconcile(List<RawEvent> events, List<ChannelStock> stocks) {
        Map<Integer, Verdict> verdicts = new HashMap<>();
        List<RawEvent> sorted = events.stream()
                .sorted(Comparator.comparing(RawEvent::occurredAt).thenComparing(RawEvent::eventNo))
                .toList();

        // ---- 1. 重量配对 ----
        Map<Long, Integer> weightNet = new HashMap<>();
        Map<Long, Boolean> weightPresent = new HashMap<>();
        reconcileWeight(sorted, verdicts, weightNet, weightPresent);

        // ---- 2. 视觉 / RFID 配对, 得投票: channel -> (sku -> [visionNet, rfidNet]) ----
        Map<Long, Map<String, int[]>> votes = new HashMap<>();
        Map<Long, Boolean> rfidPresent = new HashMap<>();
        Map<Long, Boolean> visionPresent = new HashMap<>();
        reconcileVoter(sorted, "VISION", verdicts, votes, visionPresent, stocks);
        reconcileVoter(sorted, "RFID", verdicts, votes, rfidPresent, stocks);

        // ---- 3. 货道级仲裁 ----
        List<ReconciledLine> lines = new ArrayList<>();
        Set<Long> channels = new HashSet<>();
        weightNet.keySet().forEach(channels::add);
        votes.keySet().forEach(channels::add);

        for (Long ch : channels) {
            int physical = weightNet.getOrDefault(ch, 0);
            boolean hasWeight = weightPresent.getOrDefault(ch, false);
            Map<String, int[]> vote = votes.getOrDefault(ch, Map.of());

            List<String> stockedSkus = stocks.stream()
                    .filter(s -> s.channelId().equals(ch) && s.onShelfQty() > 0)
                    .map(ChannelStock::sku).distinct().sorted().toList();

            if (hasWeight && physical > 0) {
                resolveWithPhysicalAnchor(ch, physical, vote, stockedSkus, stocks, lines, verdicts);
            } else if (hasWeight) { // 重量在但净变化为 0
                resolveWeightZero(ch, vote, lines);
            } else { // 无重量数据
                resolveWithoutWeight(ch, vote,
                        visionPresent.getOrDefault(ch, false),
                        rfidPresent.getOrDefault(ch, false),
                        stockedSkus, stocks, lines);
            }
        }

        // ---- 4. 未覆盖事件兜底 ----
        for (RawEvent e : sorted) {
            verdicts.putIfAbsent(e.eventNo(), new Verdict(e.eventNo(), Verdict.IGNORED, "未参与对账的事件"));
        }

        boolean uncertain = lines.stream().anyMatch(ReconciledLine::uncertain);
        String summary = buildSummary(lines);
        List<Verdict> verdictList = verdicts.values().stream()
                .sorted(Comparator.comparing(Verdict::eventNo)).toList();
        return new ReconcileResult(lines, verdictList, uncertain, summary);
    }

    private void reconcileWeight(List<RawEvent> sorted, Map<Integer, Verdict> verdicts,
                                 Map<Long, Integer> net, Map<Long, Boolean> present) {
        List<RawEvent> picks = new ArrayList<>();
        List<RawEvent> returns = new ArrayList<>();
        for (RawEvent e : sorted) {
            if (!"WEIGHT".equals(e.modality()) || e.channelId() == null || e.weightDeltaG() == null) continue;
            present.put(e.channelId(), true);
            if (e.weightDeltaG() < 0) picks.add(e);
            else if (e.weightDeltaG() > 0) returns.add(e);
            else verdicts.put(e.eventNo(), new Verdict(e.eventNo(), Verdict.IGNORED, "重量无变化"));
        }
        boolean[] used = new boolean[returns.size()];
        for (RawEvent pick : picks) {
            int need = Math.abs(pick.weightDeltaG());
            int match = -1;
            for (int i = 0; i < returns.size(); i++) {
                RawEvent r = returns.get(i);
                if (used[i] || !r.channelId().equals(pick.channelId())) continue;
                if (r.occurredAt().isBefore(pick.occurredAt())) continue;
                int got = r.weightDeltaG();
                if (got >= need * LOW.doubleValue() && got <= need * HIGH.doubleValue()) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                used[match] = true;
                RawEvent r = returns.get(match);
                verdicts.put(pick.eventNo(), new Verdict(pick.eventNo(), Verdict.RETURN,
                        "重量拿起与放回事件#" + r.eventNo() + "配对(" + need + "g/" + r.weightDeltaG() + "g), 不计费"));
                verdicts.put(r.eventNo(), new Verdict(r.eventNo(), Verdict.RETURN,
                        "重量放回与拿起事件#" + pick.eventNo() + "配对, 不计费"));
            } else {
                verdicts.put(pick.eventNo(), new Verdict(pick.eventNo(), Verdict.PICK,
                        "重量净减 " + need + "g 无配对放回"));
                net.merge(pick.channelId(), 1, Integer::sum);
            }
        }
        for (int i = 0; i < returns.size(); i++) {
            if (!used[i]) {
                RawEvent r = returns.get(i);
                verdicts.put(r.eventNo(), new Verdict(r.eventNo(), Verdict.IGNORED,
                        "重量增重但无对应拿起, 判为噪声"));
            }
        }
    }

    private void reconcileVoter(List<RawEvent> sorted, String modality,
                                Map<Integer, Verdict> verdicts,
                                Map<Long, Map<String, int[]>> votes,
                                Map<Long, Boolean> modalityPresent,
                                List<ChannelStock> stocks) {
        int idx = "VISION".equals(modality) ? 0 : 1;
        List<RawEvent> picks = new ArrayList<>();
        List<RawEvent> returns = new ArrayList<>();
        for (RawEvent e : sorted) {
            if (!modality.equals(e.modality()) || e.sku() == null) continue;
            if ("VISION".equals(modality)
                    && (e.confidence() == null || e.confidence().compareTo(MIN_VISION_CONFIDENCE) < 0)) {
                verdicts.put(e.eventNo(), new Verdict(e.eventNo(), Verdict.IGNORED,
                        "视觉置信度 " + e.confidence() + " 低于阈值 " + MIN_VISION_CONFIDENCE));
                continue;
            }
            Long ch = resolveChannel(e, stocks);
            if (ch == null) {
                verdicts.put(e.eventNo(), new Verdict(e.eventNo(), Verdict.UNCERTAIN,
                        modality + "无法确定货道"));
                continue;
            }
            modalityPresent.merge(ch, true, (a, b) -> a);
            RawEvent fixed = new RawEvent(e.eventNo(), e.occurredAt(), e.modality(), e.action(),
                    ch, e.sku(), e.weightDeltaG(), e.confidence());
            if ("PICK".equals(e.action())) picks.add(fixed);
            else if ("RETURN".equals(e.action())) returns.add(fixed);
            else verdicts.put(e.eventNo(), new Verdict(e.eventNo(), Verdict.IGNORED, "DETECT 检测事件"));
        }
        boolean[] used = new boolean[returns.size()];
        for (RawEvent pick : picks) {
            int match = -1;
            for (int i = 0; i < returns.size(); i++) {
                RawEvent r = returns.get(i);
                if (used[i]) continue;
                if (Objects.equals(r.channelId(), pick.channelId())
                        && r.sku().equals(pick.sku())
                        && !r.occurredAt().isBefore(pick.occurredAt())) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                used[match] = true;
                RawEvent r = returns.get(match);
                verdicts.put(pick.eventNo(), new Verdict(pick.eventNo(), Verdict.RETURN,
                        modality + "拿起与放回事件#" + r.eventNo() + "配对, 不计费"));
                verdicts.put(r.eventNo(), new Verdict(r.eventNo(), Verdict.RETURN,
                        modality + "放回与拿起事件#" + pick.eventNo() + "配对, 不计费"));
            } else {
                verdicts.put(pick.eventNo(), new Verdict(pick.eventNo(), Verdict.PICK,
                        modality + "识别净拿取 " + pick.sku()));
                votes.computeIfAbsent(pick.channelId(), k -> new HashMap<>())
                        .computeIfAbsent(pick.sku(), k -> new int[2])[idx]++;
            }
        }
        for (int i = 0; i < returns.size(); i++) {
            if (!used[i]) {
                RawEvent r = returns.get(i);
                verdicts.put(r.eventNo(), new Verdict(r.eventNo(), Verdict.IGNORED,
                        modality + "放回无对应拿起, 判为噪声"));
            }
        }
    }

    private Long resolveChannel(RawEvent e, List<ChannelStock> stocks) {
        if (e.channelId() != null) return e.channelId();
        List<Long> candidates = stocks.stream()
                .filter(s -> s.sku().equals(e.sku()) && s.onShelfQty() > 0)
                .map(ChannelStock::channelId).distinct().toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** 重量为物理锚 */
    private void resolveWithPhysicalAnchor(Long ch, int physical, Map<String, int[]> vote,
                                           List<String> stockedSkus, List<ChannelStock> stocks,
                                           List<ReconciledLine> lines, Map<Integer, Verdict> verdicts) {
        if (vote.isEmpty()) {
            if (stockedSkus.size() == 1) {
                addFefoLines(ch, stockedSkus.get(0), physical, false,
                        "仅重量锚定且货道单一SKU, FEFO指定批次", stocks, lines);
            } else {
                String guess = stockedSkus.isEmpty() ? null : stockedSkus.get(0);
                lines.add(new ReconciledLine(ch, guess, null, physical, true,
                        "重量净减" + physical + "件但视觉/RFID无数据且货道存在多个SKU, 无法指认商品"));
            }
            return;
        }

        // 每个投票 SKU 的总票数与参与模态数
        String bestSku = null;
        int bestAgreeModalities = 0;
        int bestQty = 0;
        boolean conflict = false;
        for (var e : vote.entrySet()) {
            int v = e.getValue()[0], r = e.getValue()[1];
            int modal = (v > 0 ? 1 : 0) + (r > 0 ? 1 : 0);
            int qty = Math.max(v, r);
            if (modal > bestAgreeModalities || (modal == bestAgreeModalities && qty > bestQty)) {
                bestSku = e.getKey();
                bestAgreeModalities = modal;
                bestQty = qty;
            }
            if ((v > 0 && v != physical) || (r > 0 && r != physical)) conflict = true;
        }
        boolean skuOnShelf = stockedSkus.contains(bestSku);
        if (!conflict && bestQty == physical && skuOnShelf) {
            String basis = "重量净减" + physical + "件与"
                    + (bestAgreeModalities == 2 ? "视觉/RFID" : bestAgreeModalities == 1 ? "单一识别模态" : "识别")
                    + "一致";
            addFefoLines(ch, bestSku, physical, false, basis, stocks, lines);
        } else if (!conflict && bestQty == physical) {
            // 数量一致但 SKU 不在该货道 -> 疑似误识别
            lines.add(new ReconciledLine(ch, bestSku, null, physical, true,
                    "数量一致但识别SKU(" + bestSku + ")不在货道在架清单" + stockedSkus + ", 疑似错放货道或识别错误"));
        } else {
            lines.add(new ReconciledLine(ch, skuOnShelf ? bestSku : (stockedSkus.isEmpty() ? bestSku : stockedSkus.get(0)),
                    null, physical, true,
                    "重量净减" + physical + "件与识别结果" + vote + "冲突, 证据不足转人工"));
        }
    }

    /** 重量净变化为 0, 识别却报拿取 -> 疑似误识别(防误扣款) */
    private void resolveWeightZero(Long ch, Map<String, int[]> vote, List<ReconciledLine> lines) {
        vote.forEach((sku, vr) -> {
            int qty = Math.max(vr[0], vr[1]);
            if (qty > 0) {
                lines.add(new ReconciledLine(ch, sku, null, qty, true,
                        "重量无净变化(疑似拿起放回或误识别), 但视觉/RFID报告净拿取" + qty + "件, 不自动扣款转人工"));
            }
        });
    }

    /** 无重量数据: 视觉+RFID 双模态一致才确定 */
    private void resolveWithoutWeight(Long ch, Map<String, int[]> vote, boolean hasVision, boolean hasRfid,
                                      List<String> stockedSkus, List<ChannelStock> stocks,
                                      List<ReconciledLine> lines) {
        vote.forEach((sku, vr) -> {
            int v = vr[0], r = vr[1];
            boolean onShelf = stockedSkus.contains(sku);
            if (v > 0 && r > 0 && v == r && onShelf) {
                addFefoLines(ch, sku, v, false,
                        "重量数据缺失, 视觉与RFID一致(" + v + "件)", stocks, lines);
            } else {
                int qty = Math.max(v, r);
                String why;
                if (v > 0 && r > 0 && v != r) why = "视觉(" + v + ")与RFID(" + r + ")数量冲突";
                else if (!onShelf) why = "识别SKU不在货道在架清单";
                else why = "仅" + (v > 0 ? "视觉" : "RFID") + "单模态且无重量锚定";
                lines.add(new ReconciledLine(ch, onShelf ? sku : (stockedSkus.isEmpty() ? sku : stockedSkus.get(0)),
                        null, qty, true, why + ", 证据不足转人工"));
            }
        });
    }

    /** FEFO 按批次拆行 */
    private void addFefoLines(Long ch, String sku, int qty, boolean uncertain, String basis,
                              List<ChannelStock> stocks, List<ReconciledLine> lines) {
        List<ChannelStock> candidates = stocks.stream()
                .filter(s -> s.channelId().equals(ch) && s.sku().equals(sku) && s.onShelfQty() > 0)
                .sorted(Comparator.comparing(ChannelStock::expiryDate))
                .toList();
        if (candidates.isEmpty()) {
            lines.add(new ReconciledLine(ch, sku, null, qty, true,
                    basis + ", 但货道无该SKU在架批次"));
            return;
        }
        int remain = qty;
        for (ChannelStock s : candidates) {
            if (remain <= 0) break;
            int take = Math.min(remain, s.onShelfQty());
            lines.add(new ReconciledLine(ch, sku, s.batchId(), take, uncertain,
                    basis + ", FEFO批次" + s.batchId() + "(到期" + s.expiryDate() + ")"));
            remain -= take;
        }
        if (remain > 0) {
            lines.add(new ReconciledLine(ch, sku, null, remain, true,
                    basis + ", 但在架数量不足, 账实可能不符"));
        }
    }

    private String buildSummary(List<ReconciledLine> lines) {
        int charge = lines.stream().filter(l -> !l.uncertain()).mapToInt(ReconciledLine::qty).sum();
        int uncertain = lines.stream().filter(ReconciledLine::uncertain).mapToInt(ReconciledLine::qty).sum();
        return "确定拿取" + charge + "件, 证据不足" + uncertain + "件; " +
                lines.stream().map(l -> l.sku() + "x" + l.qty() + (l.uncertain() ? "(待核)" : ""))
                        .reduce((a, b) -> a + ", " + b).orElse("无商品变动");
    }
}
