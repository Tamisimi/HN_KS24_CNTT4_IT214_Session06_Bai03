# Báo cáo phân tích — Cascading Failure do thiếu timeout

## 1. Liệt kê lỗi trong code gốc

1. **RestTemplate không có connectTimeout / readTimeout**  
   → Thread bị block rất lâu (hoặc vô hạn) khi service đích không phản hồi.

2. **Hardcode địa chỉ IP + port** (`http://192.168.0.12:8082`)  
   → Không dùng service discovery, dễ hỏng khi IP thay đổi, không load-balance.

3. **Không dùng `@LoadBalanced` RestTemplate**  
   → Không tận dụng Eureka + Spring Cloud LoadBalancer.

4. **Không có try-catch / không có fallback**  
   → Exception lan thẳng lên tầng trên (order-service).

---

## 2. Cơ chế Cascading Failure chi tiết

```
product-service chậm/treo
        ↓
StockCheckClient.checkStock() giữ thread (không timeout)
        ↓
50 req/s × thời gian block dài = hàng trăm/nghìn thread stuck
        ↓
Thread pool inventory-service cạn kiệt
        ↓
inventory-service không còn phục vụ được request mới (timeout/503)
        ↓
order-service đang chờ inventory → cũng bị chậm theo
        ↓
Thread pool order-service cạn → toàn bộ hệ thống suy sụp
```

Đây chính là **cascading failure**: lỗi ở một service lan theo chuỗi phụ thuộc và kéo sập các service phía trên.

---

## 3. Cách khắc phục đã áp dụng

| Biện pháp | Giá trị |
|-----------|--------|
| connectTimeout | 1 giây |
| readTimeout | 2 giây |
| URI | `http://product-service/api/stock/{pid}` (service-id) |
| RestTemplate | `@LoadBalanced` |
| Xử lý lỗi | `catch (ResourceAccessException)` → `StockInfo.unavailable()` |

→ Mỗi lời gọi tối đa bị block ~2–3 giây, sau đó trả fallback ngay.

---

## 4. Timeout đã đủ chưa?

**Chưa đủ hoàn toàn.**  
Timeout chỉ bảo vệ **từng request**. Nếu inventory-service nhận lượng request khổng lồ, thread pool vẫn có thể bị chiếm hết bởi các request đang chờ timeout.

### Biện pháp bổ sung đề xuất

**Circuit Breaker (Resilience4j / Spring Cloud CircuitBreaker)**

- Theo dõi tỷ lệ lỗi + timeout trong một cửa sổ thời gian.
- Khi vượt ngưỡng → chuyển sang trạng thái **OPEN**: các request tiếp theo **fail-fast** (không gọi xuống nữa).
- Sau một khoảng thời gian → **HALF_OPEN** thử lại một số request.
- Kết hợp **Bulkhead** (giới hạn số concurrent call) để cô lập tài nguyên.

→ Đây là lớp bảo vệ cần thiết tiếp theo sau timeout để ngăn cascading failure ở quy mô lớn.
