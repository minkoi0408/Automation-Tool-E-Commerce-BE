package com.example.toolecommerrce.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAnalysisResultResponse {

    private String standardizedName;
    private String translatedDescription;
    private List<String> pros;
    private List<String> cons;
    private String qualitySummary;
    private BigDecimal suggestedPrice;
    private String marketInsight;
    private double competitiveScore;
    private String recommendation;

    /** Raw JSON string trả về từ Gemini (lưu vào DB) */
    private String rawJson;
}
