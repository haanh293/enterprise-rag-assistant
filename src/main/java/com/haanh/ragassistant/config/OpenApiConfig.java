package com.haanh.ragassistant.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI enterpriseRagOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Enterprise RAG Document Assistant with Semantic Cache")
                .description("Production-grade Retrieval-Augmented Generation (RAG) platform with ultra-low latency (<50ms) " +
                    "Redis Semantic Cache, Google Gemini 2.5 Flash & text-embedding-004, PostgreSQL pgvector with HNSW index, " +
                    "and strict anti-hallucination citation grounding.")
                .version("1.0.0")
                .contact(new Contact()
                    .name("Ha Anh - Senior Backend & AI Engineer")
                    .email("haanh@example.com"))
                .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
            .servers(List.of(
                new Server().url("http://localhost:" + serverPort).description("Local Development Server")
            ));
    }
}
