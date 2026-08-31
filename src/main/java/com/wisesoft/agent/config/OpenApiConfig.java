package com.wisesoft.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author yuanke
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI reportAgentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("企业报表智能体 API")
                .version("0.1.0")
                .description("受控式 Text-to-SQL：模板优先 + NL2SQL 兜底，全部查询经 SqlGuard 校验后由只读账号执行"));
    }
}
