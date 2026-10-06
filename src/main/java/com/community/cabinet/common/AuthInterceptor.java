package com.community.cabinet.common;

import com.community.cabinet.domain.entity.Staff;
import com.community.cabinet.domain.repository.StaffRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Staff-Token";
    public static final String DEVICE_HEADER = "X-Device-Key";

    private final StaffRepository staffRepository;
    private final String deviceKey;

    /** 路径前缀 -> 允许角色(ROOT 拥有全部权限) */
    private static final java.util.List<Rule> RULES = java.util.List.of(
            new Rule("/api/device", Set.of()), // 设备网关: 设备密钥或任意员工
            new Rule("/api/replenishments", Set.of(C.ROLE_REPLENISHER, C.ROLE_OPERATOR)),
            new Rule("/api/stocktakes", Set.of(C.ROLE_REPLENISHER, C.ROLE_OPERATOR)),
            new Rule("/api/removals", Set.of(C.ROLE_REPLENISHER, C.ROLE_OPERATOR)),
            new Rule("/api/temperature", Set.of(C.ROLE_REPLENISHER, C.ROLE_OPERATOR)),
            new Rule("/api/jams", Set.of(C.ROLE_REPLENISHER, C.ROLE_OPERATOR)),
            new Rule("/api/operations", Set.of(C.ROLE_OPERATOR)),
            new Rule("/api/finance", Set.of(C.ROLE_FINANCE)),
            new Rule("/api/cs", Set.of(C.ROLE_CS)),
            new Rule("/api/supplier", Set.of(C.ROLE_SUPPLIER, C.ROLE_FINANCE)),
            new Rule("/api/trace", Set.of(C.ROLE_OPERATOR, C.ROLE_FINANCE, C.ROLE_CS, C.ROLE_SUPPLIER)),
            new Rule("/api/orders/review", Set.of(C.ROLE_OPERATOR, C.ROLE_CS)),
            new Rule("/api/orders", Set.of(C.ROLE_OPERATOR, C.ROLE_CS, C.ROLE_FINANCE))
    );

    public AuthInterceptor(StaffRepository staffRepository,
                           org.springframework.core.env.Environment env) {
        this.staffRepository = staffRepository;
        this.deviceKey = env.getProperty("app.device.key", "device-secret-2026");
    }

    private record Rule(String prefix, Set<String> roles) {}

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler)
            throws Exception {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true; // swagger / actuator 等
        }

        // 设备网关允许设备密钥
        if (path.startsWith("/api/device/")) {
            String key = req.getHeader(DEVICE_HEADER);
            if (key != null && key.equals(deviceKey)) {
                return true;
            }
        }

        String token = req.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return reject(resp, 401, "缺少 " + TOKEN_HEADER);
        }
        Staff staff = staffRepository.findByToken(token)
                .filter(St -> Boolean.TRUE.equals(St.getActive()))
                .orElse(null);
        if (staff == null) {
            return reject(resp, 401, "无效令牌");
        }
        if (!C.ROLE_ROOT.equals(staff.getRole())) {
            // 主数据写操作仅管理员/运营
            if (!"GET".equals(req.getMethod()) && path.startsWith("/api/master")
                    && !Set.of(C.ROLE_OPERATOR).contains(staff.getRole())) {
                return reject(resp, 403, "主数据写操作需要 OPERATOR 权限");
            }
            for (Rule rule : RULES) {
                if (path.startsWith(rule.prefix()) && !rule.roles().contains(staff.getRole())) {
                    return reject(resp, 403, "角色 " + staff.getRole() + " 无权访问 " + rule.prefix());
                }
            }
        }
        StaffContext.set(staff);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object h, Exception ex) {
        StaffContext.clear();
    }

    private boolean reject(HttpServletResponse resp, int code, String msg) throws java.io.IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"error\":\"" + msg + "\"}");
        return false;
    }
}
