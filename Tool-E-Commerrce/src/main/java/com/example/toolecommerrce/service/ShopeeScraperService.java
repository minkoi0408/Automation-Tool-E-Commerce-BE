package com.example.toolecommerrce.service;

import com.example.toolecommerrce.entity.Product;

import java.util.List;

public interface ShopeeScraperService {

    /**
     * Crawl chi tiết 1 sản phẩm từ URL Shopee.
     */
    Product scrapeByUrl(String url);

    /**
     * Tìm kiếm theo từ khóa, trả về danh sách URL sản phẩm.
     */
    List<String> searchProductUrls(String keyword, int maxProducts);
}
