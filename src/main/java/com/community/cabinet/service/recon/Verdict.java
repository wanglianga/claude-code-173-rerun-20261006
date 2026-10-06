package com.community.cabinet.service.recon;

/** 单条原始事件的对账结论 */
public record Verdict(Integer eventNo, String result, String note) {
    public static final String PICK = "PICK";
    public static final String RETURN = "RETURN";
    public static final String UNCERTAIN = "UNCERTAIN";
    public static final String IGNORED = "IGNORED";
}
