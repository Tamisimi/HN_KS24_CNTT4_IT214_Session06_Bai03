package com.vietmart.inventory.client;

import com.vietmart.inventory.dto.StockInfo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Client kiểm tra tồn kho — ĐÃ SỬA:
 * - Dùng @LoadBalanced RestTemplate (có timeout 1s/2s)
 * - Gọi bằng service-id thay vì hardcode IP
 * - Bắt ResourceAccessException → trả StockInfo.unavailable()
 */
@Component
@RequiredArgsConstructor
public class StockCheckClient {

    private static final Logger log = LoggerFactory.getLogger(StockCheckClient.class);

    private final RestTemplate restTemplate;

    public StockInfo checkStock(Long productId) {
        try {
            // Dùng service-id "product-service" — LoadBalancer + Eureka sẽ resolve
            return restTemplate.getForObject(
                    "http://product-service/api/stock/{pid}",
                    StockInfo.class,
                    productId);
        } catch (ResourceAccessException ex) {
            // Xảy ra khi connect timeout, read timeout, hoặc connection refused
            log.warn("Stock check timeout/unavailable for productId={}. Returning fallback. Cause: {}",
                    productId, ex.getMessage());
            return StockInfo.unavailable(productId);
        }
    }
}
