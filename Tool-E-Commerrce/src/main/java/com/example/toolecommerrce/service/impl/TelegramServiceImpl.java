package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.entity.Product;
import com.example.toolecommerrce.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramServiceImpl implements TelegramService {

    @Qualifier("telegramWebClient")
    private final WebClient telegramWebClient;

    @Value("${telegram.bot.chat-id}")
    private String chatId;

    @Override
    public void sendProductSummary(Product product) {
        String message = buildProductMessage(product);
        sendMessage(message);

        // Gửi ảnh nếu có
        if (product.getImageUrl() != null && !product.getImageUrl().isBlank()) {
            sendPhoto(product.getImageUrl(), "📦 " + product.getName());
        }
    }

    @Override
    public void sendMessage(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");

        try {
            telegramWebClient.post()
                    .uri("/sendMessage")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            res -> log.info("[Telegram] Message sent successfully"),
                            err -> log.error("[Telegram] Failed to send message: {}", err.getMessage())
                    );
        } catch (Exception e) {
            log.error("[Telegram] sendMessage error: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void sendPhoto(String photoUrl, String caption) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("photo", photoUrl);
        body.put("caption", caption);
        body.put("parse_mode", "HTML");

        try {
            telegramWebClient.post()
                    .uri("/sendPhoto")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            res -> log.info("[Telegram] Photo sent successfully"),
                            err -> log.warn("[Telegram] Failed to send photo: {}", err.getMessage())
                    );
        } catch (Exception e) {
            log.warn("[Telegram] sendPhoto error: {}", e.getMessage());
        }
    }

    private String buildProductMessage(Product product) {
        StringBuilder sb = new StringBuilder();
        sb.append("🛒 <b>SẢN PHẨM MỚI ĐÃ THU THẬP</b>\n\n");
        sb.append("📌 <b>Tên:</b> ").append(escape(product.getName())).append("\n");

        if (product.getPrice() != null) {
            sb.append("💰 <b>Giá:</b> ").append(formatPrice(product.getPrice().longValue())).append(" VNĐ");
            if (product.getDiscount() != null && product.getDiscount() > 0) {
                sb.append(" (-").append(product.getDiscount()).append("%)");
            }
            sb.append("\n");
        }

        if (product.getRating() != null) {
            sb.append("⭐ <b>Đánh giá:</b> ").append(product.getRating()).append("/5");
            if (product.getReviewCount() != null) {
                sb.append(" (").append(product.getReviewCount()).append(" lượt)");
            }
            sb.append("\n");
        }

        if (product.getSoldCount() != null) {
            sb.append("📦 <b>Đã bán:</b> ").append(formatPrice(product.getSoldCount())).append("\n");
        }

        if (product.getShopName() != null) {
            sb.append("🏪 <b>Shop:</b> ").append(escape(product.getShopName())).append("\n");
        }

        if (product.getAiSummary() != null && !product.getAiSummary().isBlank()) {
            sb.append("\n🤖 <b>AI Nhận xét:</b>\n").append(escape(product.getAiSummary())).append("\n");
        }

        if (product.getProductUrl() != null) {
            sb.append("\n🔗 <a href=\"").append(product.getProductUrl()).append("\">Xem trên Shopee</a>\n");
        }

        return sb.toString();
    }

    private String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String formatPrice(long value) {
        return String.format("%,d", value).replace(",", ".");
    }
}
