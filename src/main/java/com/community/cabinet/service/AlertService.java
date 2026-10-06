package com.community.cabinet.service;

import com.community.cabinet.common.C;
import com.community.cabinet.common.Ids;
import com.community.cabinet.domain.entity.OperationAlert;
import com.community.cabinet.domain.repository.OperationAlertRepository;
import org.springframework.stereotype.Service;

/** 运营告警: 去重创建(同类型同引用的 OPEN 告警不重复) */
@Service
public class AlertService {

    private final OperationAlertRepository alertRepository;

    public AlertService(OperationAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    public OperationAlert open(String type, Long cabinetId, String refType, Long refId,
                               String severity, String message) {
        if (refType != null && refId != null
                && alertRepository.existsByAlertTypeAndRefTypeAndRefIdAndStatus(type, refType, refId, "OPEN")) {
            return null;
        }
        OperationAlert a = new OperationAlert();
        a.setAlertType(type);
        a.setCabinetId(cabinetId);
        a.setRefType(refType);
        a.setRefId(refId);
        a.setSeverity(severity);
        a.setMessage(message);
        a.setStatus("OPEN");
        a.setCreatedAt(Ids.now());
        return alertRepository.save(a);
    }

    public OperationAlert info(Long cabinetId, String type, String refType, Long refId, String message) {
        return open(type, cabinetId, refType, refId, "INFO", message);
    }

    public OperationAlert warn(Long cabinetId, String type, String refType, Long refId, String message) {
        return open(type, cabinetId, refType, refId, "WARN", message);
    }

    public OperationAlert critical(Long cabinetId, String type, String refType, Long refId, String message) {
        return open(type, cabinetId, refType, refId, "CRITICAL", message);
    }
}
