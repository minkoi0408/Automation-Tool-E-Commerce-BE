package com.example.toolecommerrce.dto.response;

import com.example.toolecommerrce.entity.ScrapeJob;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScrapeJobResponse {

    private UUID id;
    private ScrapeJob.InputType inputType;
    private String inputValue;
    private ScrapeJob.JobStatus status;
    private Integer totalProducts;
    private Integer processedProducts;
    private Integer failedProducts;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private Integer progressPercent;
}
