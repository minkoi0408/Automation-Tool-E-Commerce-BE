package com.example.toolecommerrce.service;

import com.example.toolecommerrce.dto.AiAnalysisResult;
import com.example.toolecommerrce.entity.Product;

public interface GeminiAiService {

    /**
     * Phân tích sản phẩm và trả về kết quả dạng JSON chuẩn.
     */
    AiAnalysisResult analyzeProduct(Product product);
}
