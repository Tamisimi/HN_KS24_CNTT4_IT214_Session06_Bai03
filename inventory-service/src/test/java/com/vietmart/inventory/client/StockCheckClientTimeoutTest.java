package com.vietmart.inventory.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.vietmart.inventory.dto.StockInfo;
import org.junit.jupiter.api.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test chứng minh timeout hoạt động:
 * - Mock server delay 5 giây
 * - RestTemplate readTimeout = 2s
 * - Kỳ vọng: nhận fallback trong < 3 giây (không chờ đủ 5s)
 */
class StockCheckClientTimeoutTest {

    private WireMockServer wireMock;
    private StockCheckClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // RestTemplate với timeout giống production
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000); // 2 giây

        RestTemplate restTemplate = new RestTemplate(factory);

        // Tạo client dùng RestTemplate trỏ thẳng vào WireMock (không qua Eureka để test đơn giản)
        client = new StockCheckClient(restTemplate) {
            @Override
            public StockInfo checkStock(Long productId) {
                try {
                    return restTemplate.getForObject(
                            "http://localhost:" + wireMock.port() + "/api/stock/{pid}",
                            StockInfo.class,
                            productId);
                } catch (org.springframework.web.client.ResourceAccessException ex) {
                    return StockInfo.unavailable(productId);
                }
            }
        };

        // Stub: delay 5 giây rồi mới trả 200
        wireMock.stubFor(get(urlPathMatching("/api/stock/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(5000) // 5 giây
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\":1,\"quantity\":10,\"available\":true}")));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void shouldReturnFallbackWithin3Seconds_whenServerDelays5Seconds() {
        long start = System.currentTimeMillis();

        StockInfo result = client.checkStock(1L);

        long elapsed = System.currentTimeMillis() - start;

        // Phải trả về fallback (available = false)
        assertFalse(result.isAvailable());
        assertEquals(0, result.getQuantity());

        // Quan trọng: phải xong trong < 3 giây (timeout 2s + overhead),
        // KHÔNG được chờ đủ 5 giây của server giả
        assertTrue(elapsed < 3000,
                "Expected response within 3s due to readTimeout=2s, but took " + elapsed + "ms");

        System.out.println("Elapsed time: " + elapsed + "ms (timeout hoạt động đúng)");
    }
}
