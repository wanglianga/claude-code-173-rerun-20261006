package com.community.cabinet.common;

import com.community.cabinet.domain.entity.Staff;

/** 当前请求登录员工(ThreadLocal) */
public final class StaffContext {
    private static final ThreadLocal<Staff> CURRENT = new ThreadLocal<>();
    private StaffContext() {}

    public static void set(Staff staff) { CURRENT.set(staff); }
    public static Staff get() { return CURRENT.get(); }
    public static Long staffId() { Staff s = CURRENT.get(); return s == null ? null : s.getId(); }
    public static void clear() { CURRENT.remove(); }
}
