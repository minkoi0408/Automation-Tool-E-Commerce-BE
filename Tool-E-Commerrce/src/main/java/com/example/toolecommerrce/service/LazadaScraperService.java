package com.example.toolecommerrce.service;

import com.example.toolecommerrce.entity.Product;

import java.util.List;

public interface LazadaScraperService {

    /**
     * Tìm kiếm sản phẩm trên Lazada theo keyword.
     * Trả về list URL sản phẩm.
     */
    List<String> searchProductUrls(String keyword, int maxProducts);

    /**
     * Scrape chi tiết sản phẩm từ URL Lazada.
     */
    Product scrapeByUrl(String url);
}
