package com.example.similarproducts.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Value("${app.downstream.base-url:http://localhost:3001}")
    private String downstreamBaseUrl;

    @Value("${app.downstream.connect-timeout-ms:300}")
    private int connectTimeoutMs;

    @Value("${app.downstream.read-timeout-ms:500}")
    private int readTimeoutMs;

    @Bean
    public RestClient downstreamRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(downstreamBaseUrl)
                .requestFactory(rf)
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    public Executor detailExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
