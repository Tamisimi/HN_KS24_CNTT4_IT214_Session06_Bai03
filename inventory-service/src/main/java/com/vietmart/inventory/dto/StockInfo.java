package com.vietmart.inventory.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockInfo {

    private Long productId;
    private int quantity;
    private boolean available;

    /** Giá trị dự phòng khi không gọi được product-service / stock API */
    public static StockInfo unavailable(Long productId) {
        return StockInfo.builder()
                .productId(productId)
                .quantity(0)
                .available(false)
                .build();
    }
}
