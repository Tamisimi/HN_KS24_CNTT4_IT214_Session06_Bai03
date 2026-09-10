package com.vietmart.inventory.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Cấu hình RestTemplate:
 * - @LoadBalanced: phân giải service-id qua Eureka + LoadBalancer
 * - connectTimeout = 1s
 * - readTimeout    = 2s
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000); // 1 giây kết nối
        factory.setReadTimeout(2000);    // 2 giây đọc dữ liệu
        return new RestTemplate(factory);
    }
}
