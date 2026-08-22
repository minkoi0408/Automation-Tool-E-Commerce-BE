package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.dto.response.DashboardStatsResponse;
import com.example.toolecommerrce.dto.response.ProductResponse;
import com.example.toolecommerrce.entity.Product;
import com.example.toolecommerrce.exception.AppException;
import com.example.toolecommerrce.exception.ErrorCode;
import com.example.toolecommerrce.repository.ProductRepository;
import com.example.toolecommerrce.repository.ScrapeJobRepository;
import com.example.toolecommerrce.service.ProductService;
import com.opencsv.CSVWriter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ScrapeJobRepository scrapeJobRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> getProducts(String keyword, String category,
                                             BigDecimal minPrice, BigDecimal maxPrice,
                                             Double minRating, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return productRepository
                .searchProducts(keyword, category, minPrice, maxPrice, minRating, pageable)
                .map(this::toProductResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getProductById(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
        return toProductResponse(product);
    }

    @Override
    @Transactional
    public void deleteProduct(UUID id) {
        if (!productRepository.existsById(id)) {
            throw new AppException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        productRepository.deleteById(id);
        log.info("Deleted product with ID: {}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getAllCategories() {
        return productRepository.findAllCategories();
    }

    @Override
    public void exportToCsv(HttpServletResponse response) {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"products.csv\"");

        List<Product> products = productRepository.findAll();
        try (CSVWriter writer = new CSVWriter(
                new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.writeNext(new String[]{
                    "ID", "Tên sản phẩm", "Giá (VNĐ)", "Giá gốc (VNĐ)", "Giảm giá (%)",
                    "Đã bán", "Đánh giá", "Số lượt review",
                    "Tên shop", "URL sản phẩm", "Tóm tắt AI", "Giá gợi ý AI", "Ngày thu thập"
            });
            for (Product p : products) {
                writer.writeNext(new String[]{
                        String.valueOf(p.getId()), p.getName(),
                        p.getPrice() != null ? p.getPrice().toPlainString() : "",
                        p.getOriginalPrice() != null ? p.getOriginalPrice().toPlainString() : "",
                        p.getDiscount() != null ? p.getDiscount() + "%" : "",
                        p.getSoldCount() != null ? String.valueOf(p.getSoldCount()) : "",
                        p.getRating() != null ? String.valueOf(p.getRating()) : "",
                        p.getReviewCount() != null ? String.valueOf(p.getReviewCount()) : "",
                        p.getShopName(), p.getProductUrl(), p.getAiSummary(),
                        p.getAiSuggestedPrice() != null ? p.getAiSuggestedPrice().toPlainString() : "",
                        p.getCreatedAt() != null ? p.getCreatedAt().toString() : ""
                });
            }
        } catch (IOException e) {
            log.error("Error exporting CSV: {}", e.getMessage());
            throw new AppException(ErrorCode.EXPORT_FAILED);
        }
    }

    @Override
    public void exportToExcel(HttpServletResponse response) {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"products.xlsx\"");

        List<Product> products = productRepository.findAll();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sản phẩm");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {
                    "STT", "Tên sản phẩm", "Giá (VNĐ)", "Giá gốc", "Giảm giá",
                    "Đã bán", "Đánh giá", "Lượt review", "Shop", "URL", "Tóm tắt AI",
                    "Giá gợi ý AI", "Ngày thu thập"
            };
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.autoSizeColumn(i);
            }

            int rowNum = 1;
            for (Product p : products) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(rowNum - 1);
                row.createCell(1).setCellValue(p.getName() != null ? p.getName() : "");
                row.createCell(2).setCellValue(p.getPrice() != null ? p.getPrice().doubleValue() : 0);
                row.createCell(3).setCellValue(p.getOriginalPrice() != null ? p.getOriginalPrice().doubleValue() : 0);
                row.createCell(4).setCellValue(p.getDiscount() != null ? p.getDiscount() : 0);
                row.createCell(5).setCellValue(p.getSoldCount() != null ? p.getSoldCount() : 0);
                row.createCell(6).setCellValue(p.getRating() != null ? p.getRating() : 0);
                row.createCell(7).setCellValue(p.getReviewCount() != null ? p.getReviewCount() : 0);
                row.createCell(8).setCellValue(p.getShopName() != null ? p.getShopName() : "");
                row.createCell(9).setCellValue(p.getProductUrl() != null ? p.getProductUrl() : "");
                row.createCell(10).setCellValue(p.getAiSummary() != null ? p.getAiSummary() : "");
                row.createCell(11).setCellValue(p.getAiSuggestedPrice() != null ? p.getAiSuggestedPrice().doubleValue() : 0);
                row.createCell(12).setCellValue(p.getCreatedAt() != null ? p.getCreatedAt().toString() : "");
            }
            workbook.write(response.getOutputStream());
        } catch (IOException e) {
            log.error("Error exporting Excel: {}", e.getMessage());
            throw new AppException(ErrorCode.EXPORT_FAILED);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardStatsResponse getDashboardStats() {
        return DashboardStatsResponse.builder()
                .totalProducts(productRepository.count())
                .completedProducts(productRepository.countByStatus(Product.ProductStatus.COMPLETED))
                .failedProducts(productRepository.countByStatus(Product.ProductStatus.FAILED))
                .totalJobs(scrapeJobRepository.count())
                .build();
    }

    // -------------------------------------------------------------------------
    // Private mapping (Entity -> Response) - logic nằm ở service
    // -------------------------------------------------------------------------

    private ProductResponse toProductResponse(Product p) {
        return ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .price(p.getPrice())
                .originalPrice(p.getOriginalPrice())
                .discount(p.getDiscount())
                .soldCount(p.getSoldCount())
                .rating(p.getRating())
                .reviewCount(p.getReviewCount())
                .shopName(p.getShopName())
                .shopLocation(p.getShopLocation())
                .imageUrl(p.getImageUrl())
                .productUrl(p.getProductUrl())
                .category(p.getCategory())
                .specifications(p.getSpecifications())
                .description(p.getDescription())
                .aiAnalysis(p.getAiAnalysis())
                .aiSuggestedPrice(p.getAiSuggestedPrice())
                .aiSummary(p.getAiSummary())
                .source(p.getSource())
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
