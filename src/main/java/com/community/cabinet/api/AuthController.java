package com.community.cabinet.api;

import com.community.cabinet.common.StaffContext;
import com.community.cabinet.domain.entity.Staff;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "账号")
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @GetMapping("/me")
    public Staff me() { return StaffContext.get(); }
}
