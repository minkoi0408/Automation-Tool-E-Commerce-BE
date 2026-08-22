package com.example.toolecommerrce.dto.response;

import com.example.toolecommerrce.entity.Product;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {

    private UUID id;
    private String name;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer discount;
    private Long soldCount;
    private Double rating;
    private Long reviewCount;
    private String shopName;
    private String shopLocation;
    private String imageUrl;
    private String productUrl;
    private String category;
    private String specifications;
    private String description;
    private String aiAnalysis;
    private BigDecimal aiSuggestedPrice;
    private String aiSummary;
    private Product.ProductSource source;
    private Product.ProductStatus status;
    private LocalDateTime createdAt;
}
