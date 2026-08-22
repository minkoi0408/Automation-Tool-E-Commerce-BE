package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.dto.response.AiAnalysisResultResponse;
import com.example.toolecommerrce.entity.Product;
import com.example.toolecommerrce.service.GeminiAiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class GeminiAiServiceImpl implements GeminiAiService {

    @Qualifier("geminiWebClient")
    private final WebClient geminiWebClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key}")
    private String apiKey;

    @Override
    public AiAnalysisResultResponse analyzeProduct(Product product) {
        log.info("[Gemini] Analyzing product: {}", product.getName());
        String prompt = buildAnalysisPrompt(product);
        String rawResponse = callGemini(prompt);
        return parseAnalysisResult(rawResponse);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildAnalysisPrompt(Product product) {
        return String.format("""
                Bạn là chuyên gia phân tích sản phẩm thương mại điện tử.
                Hãy phân tích thông tin sản phẩm sau đây từ Shopee và trả về kết quả CHÍNH XÁC theo định dạng JSON.
                
                **Thông tin sản phẩm:**
                - Tên: %s
                - Giá bán: %s VNĐ
                - Giá gốc: %s VNĐ
                - Số lượng đã bán: %s
                - Đánh giá: %s/5.0 (%s lượt)
                - Mô tả: %s
                - Thông số kỹ thuật: %s
                
                **Yêu cầu:**
                Trả về ĐÚNG JSON format sau (không thêm markdown, không thêm giải thích):
                {
                  "pros": ["ưu điểm 1", "ưu điểm 2", "ưu điểm 3"],
                  "cons": ["nhược điểm 1", "nhược điểm 2"],
                  "qualitySummary": "Tóm tắt chất lượng sản phẩm trong 2-3 câu",
                  "suggestedPrice": 150000,
                  "marketInsight": "Nhận xét về vị thế sản phẩm trên thị trường",
                  "competitiveScore": 7.5,
                  "recommendation": "Nên mua / Cân nhắc / Không nên mua"
                }
                """,
                product.getName(),
                product.getPrice() != null ? product.getPrice().toPlainString() : "N/A",
                product.getOriginalPrice() != null ? product.getOriginalPrice().toPlainString() : "N/A",
                product.getSoldCount() != null ? product.getSoldCount() : "N/A",
                product.getRating() != null ? product.getRating() : "N/A",
                product.getReviewCount() != null ? product.getReviewCount() : "N/A",
                product.getDescription() != null ? product.getDescription() : "Không có mô tả",
                product.getSpecifications() != null ? product.getSpecifications() : "Không có thông số"
        );
    }

    private String callGemini(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                ),
                "generationConfig", Map.of(
                        "temperature", 0.3,
                        "topK", 40,
                        "topP", 0.95,
                        "maxOutputTokens", 1024
                )
        );

        try {
            String response = geminiWebClient.post()
                    .uri("?key=" + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            return root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text")
                    .asText("");
        } catch (Exception e) {
            log.error("[Gemini] API call failed: {}", e.getMessage());
            return "{}";
        }
    }

    private AiAnalysisResultResponse parseAnalysisResult(String rawJson) {
        // Xử lý nếu Gemini trả về markdown code block
        String cleanJson = rawJson.trim();
        if (cleanJson.startsWith("```json")) cleanJson = cleanJson.substring(7);
        if (cleanJson.startsWith("```")) cleanJson = cleanJson.substring(3);
        if (cleanJson.endsWith("```")) cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
        cleanJson = cleanJson.trim();

        try {
            JsonNode node = objectMapper.readTree(cleanJson);

            List<String> pros = new ArrayList<>();
            List<String> cons = new ArrayList<>();
            node.path("pros").forEach(p -> pros.add(p.asText()));
            node.path("cons").forEach(c -> cons.add(c.asText()));

            BigDecimal suggestedPrice = null;
            if (!node.path("suggestedPrice").isMissingNode()) {
                suggestedPrice = BigDecimal.valueOf(node.path("suggestedPrice").asDouble());
            }

            return AiAnalysisResultResponse.builder()
                    .pros(pros)
                    .cons(cons)
                    .qualitySummary(node.path("qualitySummary").asText(""))
                    .suggestedPrice(suggestedPrice)
                    .marketInsight(node.path("marketInsight").asText(""))
                    .competitiveScore(node.path("competitiveScore").asDouble(0))
                    .recommendation(node.path("recommendation").asText(""))
                    .rawJson(cleanJson)
                    .build();
        } catch (Exception e) {
            log.error("[Gemini] Failed to parse AI response: {}", e.getMessage());
            return AiAnalysisResultResponse.builder()
                    .pros(List.of())
                    .cons(List.of())
                    .qualitySummary("Không thể phân tích")
                    .rawJson(rawJson)
                    .build();
        }
    }
}
