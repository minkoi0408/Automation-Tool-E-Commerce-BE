package com.example.toolecommerrce.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // -------------------------------------------------------------------------
    // System errors
    // -------------------------------------------------------------------------
    INTERNAL_SERVER_ERROR("Đã xảy ra lỗi hệ thống", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_INPUT("Dữ liệu đầu vào không hợp lệ", HttpStatus.BAD_REQUEST),
    VALIDATION_ERROR("Lỗi xác thực dữ liệu", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Product errors
    // -------------------------------------------------------------------------
    PRODUCT_NOT_FOUND("Không tìm thấy sản phẩm", HttpStatus.NOT_FOUND),

    // -------------------------------------------------------------------------
    // ScrapeJob errors
    // -------------------------------------------------------------------------
    SCRAPE_JOB_NOT_FOUND("Không tìm thấy job crawl", HttpStatus.NOT_FOUND),
    SCRAPE_JOB_ALREADY_RUNNING("Job crawl đang được xử lý, vui lòng chờ", HttpStatus.CONFLICT),
    SCRAPE_FAILED("Crawl dữ liệu thất bại", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_SCRAPE_URL("URL không hợp lệ hoặc không phải Shopee", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // AI errors
    // -------------------------------------------------------------------------
    AI_ANALYSIS_FAILED("Phân tích AI thất bại", HttpStatus.SERVICE_UNAVAILABLE),
    AI_API_KEY_INVALID("Gemini API Key không hợp lệ", HttpStatus.UNAUTHORIZED),

    // -------------------------------------------------------------------------
    // Telegram errors
    // -------------------------------------------------------------------------
    TELEGRAM_SEND_FAILED("Gửi thông báo Telegram thất bại", HttpStatus.SERVICE_UNAVAILABLE),

    // -------------------------------------------------------------------------
    // Validation errors (dùng trong @Valid annotation)
    // -------------------------------------------------------------------------
    INPUT_VALUE_BLANK("inputValue không được để trống", HttpStatus.BAD_REQUEST),
    INPUT_TYPE_NULL("inputType không được để trống", HttpStatus.BAD_REQUEST),
    MAX_PRODUCTS_INVALID("maxProducts phải >= 1", HttpStatus.BAD_REQUEST),

    // -------------------------------------------------------------------------
    // Export errors
    // -------------------------------------------------------------------------
    EXPORT_FAILED("Xuất dữ liệu thất bại", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String message, HttpStatus httpStatus) {
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
