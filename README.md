# Bài 3 Session 06 — Khắc phục cascading failure do thiếu timeout trong RestTemplate

**Cấp độ:** Vận dụng chuyên sâu  
**Session 06 — Giao tiếp đồng bộ & Fault Isolation**

---

## 1. Các lỗi trong code gốc

```java
@Component
public class StockCheckClient {
    @Autowired
    private RestTemplate restTemplate;   // (1) không có timeout, không @LoadBalanced

    public StockInfo checkStock(Long productId) {
        return restTemplate.getForObject(
            "http://192.168.0.12:8082/api/stock/{pid}",  // (2) hardcode IP + port
            StockInfo.class, productId);
        // (3) không try-catch → không fallback
    }
}
```

| # | Lỗi | Hậu quả |
|---|-----|--------|
| 1 | RestTemplate **không cấu hình timeout** | Thread bị block vô hạn (hoặc rất lâu) khi service đích chậm/treo |
| 2 | **Hardcode IP:port** (`192.168.0.12:8082`) | Không dùng Eureka, không load-balance, chết cứng khi IP đổi |
| 3 | **Không có @LoadBalanced** | Không tận dụng được Discovery + LoadBalancer |
| 4 | **Không xử lý exception / không fallback** | Lỗi lan thẳng lên caller (order-service) |

---

## 2. Cơ chế Cascading Failure (từng bước)

1. **product-service** (hoặc stock API) bị chậm / treo → không trả lời trong thời gian dài.
2. Mỗi request gọi `checkStock()` giữ **1 thread** của inventory-service trong suốt thời gian chờ (mặc định có thể hàng chục giây hoặc vô hạn).
3. Vào giờ cao điểm: **50 request/giây** × thời gian block dài → hàng trăm / hàng nghìn thread bị stuck.
4. **Thread pool** của Tomcat (inventory-service) cạn kiệt → không còn thread phục vụ request mới.
5. inventory-service bắt đầu trả **timeout / 503** cho order-service.
6. order-service cũng bị chậm theo (vì đang chờ inventory) → thread pool order-service cũng cạn → **toàn hệ thống bị kéo sập** (cascading failure).

---

## 3. Code đã sửa đúng chuẩn

### 3.1. Cấu hình RestTemplate có timeout + @LoadBalanced

```java
@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);  // 1 giây
        factory.setReadTimeout(2000);     // 2 giây
        return new RestTemplate(factory);
    }
}
```

### 3.2. StockCheckClient đã sửa

```java
@Component
@RequiredArgsConstructor
public class StockCheckClient {

    private final RestTemplate restTemplate;  // đã @LoadBalanced + timeout

    public StockInfo checkStock(Long productId) {
        try {
            return restTemplate.getForObject(
                    "http://product-service/api/stock/{pid}",  // dùng service-id
                    StockInfo.class,
                    productId);
        } catch (ResourceAccessException ex) {
            // connect/read timeout hoặc connection refused
            return StockInfo.unavailable(productId);
        }
    }
}
```

---

## 4. Xác minh bằng test (timeout hoạt động)

Ý tưởng test tích hợp:

- Dùng **WireMock / MockWebServer** giả lập server delay **5 giây**.
- Gọi `checkStock()`.
- **Kỳ vọng:** nhận fallback trong **≤ 3 giây** (vì readTimeout = 2s + overhead), **không** chờ đủ 5 giây.

File test: `StockCheckClientTimeoutTest.java`

---

## 5. Phân tích sau khi đã có timeout

> Sau khi fix timeout, order-service **có thể vẫn bị ảnh hưởng** nếu inventory-service bị **tràn ngập request** không?

**Có.**  
Timeout chỉ giới hạn thời gian **một** thread bị giữ. Nếu lượng request tới inventory-service quá lớn (ví dụ 1000 req/s) trong khi capacity chỉ 200 thread → inventory vẫn có thể quá tải, trả chậm/lỗi → order-service vẫn nhận nhiều lỗi/fallback.

### Biện pháp bổ sung đề xuất (không cần code)

1. **Circuit Breaker (Resilience4j)**  
   - Khi tỷ lệ lỗi / timeout vượt ngưỡng → **mở mạch** (OPEN).  
   - Các request tiếp theo fail-fast ngay, không gọi xuống inventory nữa.  
   - Sau một thời gian thử lại (HALF_OPEN).

2. **Bulkhead / Semaphore isolation**  
   - Giới hạn số thread tối đa được phép gọi inventory-service (ví dụ chỉ 20 thread).  
   - Phần còn lại của inventory/order vẫn phục vụ được các API khác.

3. **Rate limiting / Queue**  
   - Giới hạn số request chấp nhận vào inventory-service.

→ Timeout là lớp bảo vệ **thứ nhất**. Circuit Breaker + Bulkhead là lớp bảo vệ **thứ hai** để ngăn cascade khi lưu lượng cực lớn.

---

## 6. Cấu trúc repository

```
HN_KS24_CNTT4_IT214_Session06_Bai03/
├── README.md
├── BAO_CAO_PHAN_TICH.md
└── inventory-service/
    ├── build.gradle
    └── src/main/java/com/vietmart/inventory/
        ├── config/RestTemplateConfig.java
        ├── client/StockCheckClient.java
        ├── dto/StockInfo.java
        └── ...
```
