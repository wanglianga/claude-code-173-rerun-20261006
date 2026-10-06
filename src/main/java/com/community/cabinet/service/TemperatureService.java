package com.community.cabinet.service;

import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.*;
import com.community.cabinet.domain.repository.*;
import com.community.cabinet.kafka.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 温度监控: 柜机持续上报温度, 超出温层允许区间 -> 温度日志 + 告警 + 柜机置 TEMP_ALARM;
 * 处置动作: 隔离在架商品(不可售卖) -> 复核合格解除 或 报损(温控变质, 计入供应商结算损耗)。
 */
@Service
public class TemperatureService {

    private final TemperatureLogRepository logRepository;
    private final TemperatureAlarmRepository alarmRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final BatchInventoryRepository inventoryRepository;
    private final ProductBatchRepository batchRepository;
    private final InventoryService inventoryService;
    private final AlertService alertService;
    private final EventPublisher publisher;

    public TemperatureService(TemperatureLogRepository logRepository,
                              TemperatureAlarmRepository alarmRepository,
                              CabinetRepository cabinetRepository,
                              ChannelRepository channelRepository,
                              BatchInventoryRepository inventoryRepository,
                              ProductBatchRepository batchRepository,
                              InventoryService inventoryService,
                              AlertService alertService,
                              EventPublisher publisher) {
        this.logRepository = logRepository;
        this.alarmRepository = alarmRepository;
        this.cabinetRepository = cabinetRepository;
        this.channelRepository = channelRepository;
        this.inventoryRepository = inventoryRepository;
        this.batchRepository = batchRepository;
        this.inventoryService = inventoryService;
        this.alertService = alertService;
        this.publisher = publisher;
    }

    /** 记录一次温度采样; abnormal 由调用方(设备/阈值)判断或本方法按柜机温层判断 */
    @Transactional
    public TemperatureLog record(String cabinetCode, BigDecimal tempC, String sensor) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> new IllegalArgumentException("柜机不存在: " + cabinetCode));
        String status = judgeStatus(cabinet, tempC);
        TemperatureLog log = new TemperatureLog();
        log.setCabinetId(cabinet.getId());
        log.setRecordedAt(Ids.now());
        log.setTempC(tempC);
        log.setSensor(sensor);
        log.setStatus(status);
        logRepository.save(log);

        if (!"NORMAL".equals(status)) {
            onAbnormal(cabinet, tempC, status);
        } else {
            onRecovered(cabinet, tempC);
        }
        return log;
    }

    private String judgeStatus(Cabinet cabinet, BigDecimal t) {
        return switch (cabinet.getTempType()) {
            // 冷藏 0~8°C(简化阈值, MIXED 柜按冷藏保守阈值)
            case "CHILLED", "MIXED" -> (t.compareTo(BigDecimal.valueOf(8)) > 0) ? "HIGH"
                    : (t.compareTo(BigDecimal.ZERO) < 0 ? "LOW" : "NORMAL");
            // 冷冻 <= -15°C
            case "FROZEN" -> t.compareTo(BigDecimal.valueOf(-15)) > 0 ? "HIGH" : "NORMAL";
            // 常温 <= 30°C
            default -> t.compareTo(BigDecimal.valueOf(30)) > 0 ? "HIGH" : "NORMAL";
        };
    }

    private void onAbnormal(Cabinet cabinet, BigDecimal tempC, String status) {
        TemperatureAlarm alarm = alarmRepository
                .findFirstByCabinetIdAndStatusOrderByStartedAtDesc(cabinet.getId(), "ACTIVE")
                .orElseGet(() -> {
                    TemperatureAlarm a = new TemperatureAlarm();
                    a.setCabinetId(cabinet.getId());
                    a.setStartedAt(Ids.now());
                    a.setStatus("ACTIVE");
                    a.setPeakC(tempC);
                    alarmRepository.save(a);
                    cabinet.setStatus("TEMP_ALARM");
                    cabinetRepository.save(cabinet);
                    alertService.critical(cabinet.getId(), C.ALERT_TEMP, "TEMP_ALARM", a.getId(),
                            "柜机 " + cabinet.getCode() + " 温度异常 " + status + " " + tempC + "°C");
                    publisher.publish("TEMP_ALARM_RAISED", cabinet.getCode(), Map.of(
                            "cabinetCode", cabinet.getCode(), "tempC", tempC.toPlainString(),
                            "alarmId", a.getId()));
                    return a;
                });
        if (alarm.getPeakC() == null || tempC.compareTo(alarm.getPeakC()) > 0) {
            alarm.setPeakC(tempC);
            alarmRepository.save(alarm);
        }
    }

    private void onRecovered(Cabinet cabinet, BigDecimal tempC) {
        alarmRepository.findFirstByCabinetIdAndStatusOrderByStartedAtDesc(cabinet.getId(), "ACTIVE")
                .ifPresent(alarm -> {
                    alarm.setEndedAt(Ids.now());
                    alarm.setStatus("RECOVERED");
                    alarmRepository.save(alarm);
                    if ("TEMP_ALARM".equals(cabinet.getStatus())) {
                        cabinet.setStatus("NORMAL");
                        cabinetRepository.save(cabinet);
                    }
                    alertService.info(cabinet.getId(), C.ALERT_TEMP, "TEMP_ALARM", alarm.getId(),
                            "柜机 " + cabinet.getCode() + " 温度恢复正常 " + tempC + "°C, 等待商品处置复核");
                    publisher.publish("TEMP_ALARM_RECOVERED", cabinet.getCode(), Map.of(
                            "cabinetCode", cabinet.getCode(), "alarmId", alarm.getId()));
                });
    }

    /** 隔离: 温度异常期间把柜内在架批次全部移入温控隔离(不可售卖) */
    @Transactional
    public TemperatureAlarm quarantineCabinet(Long alarmId) {
        TemperatureAlarm alarm = alarmRepository.findById(alarmId)
                .orElseThrow(() -> new IllegalArgumentException("告警不存在"));
        List<BatchInventory> rows = inventoryRepository.findByCabinetId(alarm.getCabinetId()).stream()
                .filter(i -> i.getOnShelfQty() > 0).toList();
        for (BatchInventory inv : rows) {
            inventoryService.quarantine(inv.getBatchId(), inv.getChannelId(), inv.getCabinetId(),
                    batchRepository.findById(inv.getBatchId()).map(ProductBatch::getSku).orElse("?"),
                    inv.getOnShelfQty(), "TEMP_ALARM", alarmId);
        }
        alarm.setStatus("DISPOSED");
        alarm.setActionTaken("QUARANTINE");
        alarmRepository.save(alarm);
        publisher.publish("TEMP_QUARANTINED", String.valueOf(alarm.getCabinetId()), Map.of(
                "alarmId", alarmId, "quarantinedRows", rows.size()));
        return alarm;
    }

    /** 复核合格: 隔离商品解除回架 */
    @Transactional
    public void release(Long alarmId, Long batchId, Long channelId, int qty, Long staffId, String note) {
        TemperatureAlarm alarm = alarmRepository.findById(alarmId)
                .orElseThrow(() -> new IllegalArgumentException("告警不存在"));
        BatchInventory inv = inventoryRepository.findByBatchIdAndChannelId(batchId, channelId)
                .orElseThrow(() -> new IllegalArgumentException("隔离库存不存在"));
        inventoryService.releaseQuarantine(batchId, channelId, inv.getCabinetId(),
                batchRepository.findById(batchId).map(ProductBatch::getSku).orElse("?"),
                qty, "TEMP_ALARM", alarmId, staffId);
    }

    @Transactional(readOnly = true)
    public List<TemperatureLog> recentLogs(String cabinetCode) {
        Cabinet cabinet = cabinetRepository.findByCode(cabinetCode)
                .orElseThrow(() -> new IllegalArgumentException("柜机不存在"));
        return logRepository.findTop20ByCabinetIdOrderByRecordedAtDesc(cabinet.getId());
    }
}
