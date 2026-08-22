package com.example.toolecommerrce.controller;

import com.example.toolecommerrce.dto.ApiResponse;
import com.example.toolecommerrce.dto.response.DashboardStatsResponse;
import com.example.toolecommerrce.dto.response.ProductResponse;
import com.example.toolecommerrce.service.ProductService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> getProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Double minRating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(ApiResponse.<Page<ProductResponse>>builder()
                .success(true)
                .message("Thành công")
                .data(productService.getProducts(keyword, category, minPrice, maxPrice, minRating, page, size))
                .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.<ProductResponse>builder()
                .success(true)
                .message("Thành công")
                .data(productService.getProductById(id))
                .build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Đã xóa sản phẩm thành công")
                .build());
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<String>>> getAllCategories() {
        return ResponseEntity.ok(ApiResponse.<List<String>>builder()
                .success(true)
                .message("Thành công")
                .data(productService.getAllCategories())
                .build());
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<DashboardStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResponse.<DashboardStatsResponse>builder()
                .success(true)
                .message("Thành công")
                .data(productService.getDashboardStats())
                .build());
    }

    @GetMapping("/export")
    public void exportProducts(
            @RequestParam(defaultValue = "csv") String format,
            HttpServletResponse response
    ) {
        if ("excel".equalsIgnoreCase(format)) {
            productService.exportToExcel(response);
        } else {
            productService.exportToCsv(response);
        }
    }
}
