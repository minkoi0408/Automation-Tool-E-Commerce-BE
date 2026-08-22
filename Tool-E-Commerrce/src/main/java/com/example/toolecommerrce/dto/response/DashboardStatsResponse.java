package com.example.toolecommerrce.dto.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsResponse {
    private long totalProducts;
    private long completedProducts;
    private long failedProducts;
    private long totalJobs;
}
