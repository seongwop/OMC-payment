package com.omc.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonSwaggerAutoConfiguration {

    @Value("${app.swagger.server-url:}")
    private String swaggerServerUrl;

    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI gatewayHeaderOpenAPI() {
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Keycloak에서 발급받은 Access Token")));
    }

    @Bean
    public OpenApiCustomizer swaggerServerUrlCustomizer() {
        return openApi -> {
            if (swaggerServerUrl != null && !swaggerServerUrl.isBlank()) {
                openApi.setServers(List.of(new Server().url(swaggerServerUrl)));
            }
        };
    }
}
