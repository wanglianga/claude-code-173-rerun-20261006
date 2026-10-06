package com.community.cabinet.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI cabinetOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("无人货柜补货盘点临期下架服务 API")
                        .version("1.0.0")
                        .description("补货/三模识别售卖/温控/临期下架/盘点/结算/售后证据回放"))
                .components(new Components().addSecuritySchemes("StaffToken",
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Staff-Token")))
                .addSecurityItem(new SecurityRequirement().addList("StaffToken"));
    }
}
