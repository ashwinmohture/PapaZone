package com.papazone.ai.config;

import com.papazone.ai.security.JwtVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

@Configuration
public class ClientConfig {

    /** HTTP client for the LLM provider (long read timeout). */
    @Bean
    RestClient llmRestClient(RestClient.Builder builder, AiProperties props) {
        return builder
                .baseUrl(props.llm().baseUrl())
                .requestFactory(factory(Duration.ofSeconds(5), props.llm().timeout()))
                .build();
    }

    /** HTTP client for sibling services (short timeouts, fail fast). */
    @Bean
    RestClient internalRestClient(RestClient.Builder builder) {
        return builder
                .requestFactory(factory(Duration.ofSeconds(3), Duration.ofSeconds(10)))
                .build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtVerifier jwtVerifier(AiProperties props, Clock clock) {
        return new JwtVerifier(props.security().jwtSecret(), clock);
    }

    @Bean
    ZoneId businessZone(AiProperties props) {
        return ZoneId.of(props.timezone());
    }

    private static SimpleClientHttpRequestFactory factory(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connect);
        f.setReadTimeout(read);
        return f;
    }
}
