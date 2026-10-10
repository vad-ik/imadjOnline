package com.github.vad_ik.imadjOnline.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI imadjOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Imadj Online API")
                        .version("0.1.0")
                        .description("""
                                HTTP API для лобби, колод, авторизации и игровых ходов.

                                **WebSocket** (`/ws/game`) не входит в OpenAPI/Swagger — для сокетов
                                используется спецификация **AsyncAPI**: см. `/docs/asyncapi.yaml`.

                                Cookie-сессия + `rememberMe` после логина. Гостевой `clientId` cookie.
                                """)
                        .license(new License().name("Private")))
                .servers(List.of(new Server().url("/").description("Current host")));
    }
}
