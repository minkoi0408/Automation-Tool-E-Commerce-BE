package com.example.toolecommerrce.service;

import com.example.toolecommerrce.dto.response.DashboardStatsResponse;
import com.example.toolecommerrce.dto.response.ProductResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ProductService {

    Page<ProductResponse> getProducts(
            String keyword,
            String category,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Double minRating,
            int page,
            int size
    );

    ProductResponse getProductById(UUID id);

    void deleteProduct(UUID id);

    List<String> getAllCategories();

    void exportToCsv(HttpServletResponse response);

    void exportToExcel(HttpServletResponse response);

    DashboardStatsResponse getDashboardStats();
}
