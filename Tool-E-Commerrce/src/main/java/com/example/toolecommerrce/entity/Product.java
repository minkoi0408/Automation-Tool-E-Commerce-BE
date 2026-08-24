package com.example.toolecommerrce.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", columnDefinition = "TEXT")
    private String name;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "original_price")
    private BigDecimal originalPrice;

    @Column(name = "discount")
    private Integer discount;

    @Column(name = "sold_count")
    private Long soldCount;

    @Column(name = "rating")
    private Double rating;

    @Column(name = "review_count")
    private Long reviewCount;

    @Column(name = "shop_name")
    private String shopName;

    @Column(name = "shop_sold_count")
    private String shopSoldCount;

    @Column(name = "shop_location")
    private String shopLocation;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "product_url", columnDefinition = "TEXT")
    private String productUrl;

    @Column(name = "category")
    private String category;

    @Column(name = "specifications", columnDefinition = "TEXT")
    private String specifications;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "ai_analysis", columnDefinition = "TEXT")
    private String aiAnalysis;

    @Column(name = "ai_suggested_price")
    private BigDecimal aiSuggestedPrice;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "source")
    private ProductSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ProductStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scrape_job_id")
    private ScrapeJob scrapeJob;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ProductSource {
        SHOPEE, LAZADA, TAOBAO
    }

    public enum ProductStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
