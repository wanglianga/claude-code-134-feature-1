package com.training.crm.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI 3.x 文档元信息（Swagger UI：/swagger-ui.html） */
@Configuration
class OpenApiConfig {
    @Bean
    fun apiInfo(): OpenAPI = OpenAPI().info(
        Info()
            .title("培训机构试听排班转化与课消服务 API")
            .version("1.0.0")
            .description(
                "线索 → 试听排班（多次改约容量回收/候补/主管审批）→ 试听反馈 → 报名转化 → 排课课消 → 退费 的完整闭环。" +
                    "鉴权使用请求头 X-Role(ADMIN/PRINCIPAL/CONSULTANT/TEACHER) 与 X-Actor。"
            )
    )
}
