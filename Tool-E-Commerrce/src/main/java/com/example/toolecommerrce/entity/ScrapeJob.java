package com.example.toolecommerrce.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "scrape_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScrapeJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false)
    private InputType inputType;

    @Column(name = "input_value", columnDefinition = "TEXT", nullable = false)
    private String inputValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private JobStatus status;

    @Column(name = "total_products")
    private Integer totalProducts;

    @Column(name = "processed_products")
    private Integer processedProducts;

    @Column(name = "failed_products")
    private Integer failedProducts;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @OneToMany(mappedBy = "scrapeJob", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Builder.Default
    private List<Product> products = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum InputType {
        URL, KEYWORD
    }

    public enum JobStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
