package com.example.toolecommerrce.service;

import com.example.toolecommerrce.entity.Product;

public interface TelegramService {

    /**
     * Gửi bản tin tóm tắt sản phẩm qua Telegram.
     */
    void sendProductSummary(Product product);

    /**
     * Gửi tin nhắn văn bản đơn thuần qua Telegram.
     */
    void sendMessage(String message);
}
