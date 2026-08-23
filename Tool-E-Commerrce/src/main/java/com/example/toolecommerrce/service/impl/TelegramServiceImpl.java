package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.entity.Product;
import com.example.toolecommerrce.service.TelegramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramServiceImpl implements TelegramService {

    @Qualifier("telegramWebClient")
    private final WebClient telegramWebClient;
    private final ObjectMapper objectMapper;

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
        sb.append("🛒 <b>BÁO CÁO PHÂN TÍCH SẢN PHẨM</b>\n\n");
        sb.append("📌 <b>Tên sản phẩm:</b> ").append(escape(product.getName())).append("\n");

        // ── GIÁ ─────────────────────────────────────────────────────────────
        if (product.getPrice() != null) {
            sb.append("💰 <b>Giá bán:</b> ").append(formatPrice(product.getPrice().longValue())).append(" VNĐ");
            if (product.getDiscount() != null && product.getDiscount() > 0) {
                sb.append(" <b>(-").append(product.getDiscount()).append("%)</b>");
            }
            sb.append("\n");

            if (product.getOriginalPrice() != null
                    && product.getOriginalPrice().compareTo(product.getPrice()) > 0) {
                sb.append("🏷 <b>Giá gốc:</b> <s>")
                        .append(formatPrice(product.getOriginalPrice().longValue()))
                        .append(" VNĐ</s>\n");
            }
        }

        // ── SHOP BÁN & SỐ LƯỢNG BÁN CỦA SHOP ────────────────────────────────
        String shop = (product.getShopName() != null && !product.getShopName().isBlank())
                ? product.getShopName() : "Shop trên sàn TMĐT";
        sb.append("🏪 <b>Shop bán:</b> ").append(escape(shop));
        if (product.getShopSoldCount() != null && !product.getShopSoldCount().isBlank()) {
            sb.append(" (").append(escape(product.getShopSoldCount())).append(" đã bán)");
        }
        sb.append("\n");

        // ── ĐÃ BÁN CỦA SẢN PHẨM & ĐÁNH GIÁ ──────────────────────────────────
        if (product.getSoldCount() != null && product.getSoldCount() > 0) {
            sb.append("📦 <b>Đã bán:</b> ").append(formatCount(product.getSoldCount())).append(" sản phẩm\n");
        }

        if (product.getRating() != null && product.getRating() > 0) {
            sb.append("⭐ <b>Đánh giá:</b> ").append(String.format("%.1f", product.getRating())).append("/5.0 sao");
            if (product.getReviewCount() != null && product.getReviewCount() > 0) {
                sb.append(" (").append(formatCount(product.getReviewCount())).append(" lượt đánh giá)");
            }
            sb.append("\n");
        } else {
            sb.append("⭐ <b>Đánh giá:</b> Chưa có đánh giá (Sản phẩm mới)\n");
        }

        // ── AI PHÂN TÍCH & ĐỊNH GIÁ CẠNH TRANH ──────────────────────────────
        JsonNode aiNode = null;
        if (product.getAiAnalysis() != null && !product.getAiAnalysis().isBlank()) {
            try {
                aiNode = objectMapper.readTree(product.getAiAnalysis());
            } catch (Exception ignored) {}
        }

        sb.append("\n────────────────────\n");
        sb.append("🤖 <b>AI PHÂN TÍCH & ĐỊNH GIÁ CẠNH TRANH</b>\n\n");

        if (product.getAiSummary() != null && !product.getAiSummary().isBlank()) {
            sb.append("🌟 <b>Tóm tắt chất lượng & Đánh giá khách hàng:</b>\n")
                    .append(escape(product.getAiSummary())).append("\n\n");
        }

        if (aiNode != null) {
            // Ưu điểm
            JsonNode pros = aiNode.path("pros");
            if (pros.isArray() && pros.size() > 0) {
                sb.append("✅ <b>Ưu điểm nổi bật:</b>\n");
                for (JsonNode pro : pros) {
                    sb.append("• ").append(escape(pro.asText())).append("\n");
                }
                sb.append("\n");
            }

            // Nhược điểm
            JsonNode cons = aiNode.path("cons");
            if (cons.isArray() && cons.size() > 0) {
                sb.append("⚠️ <b>Nhược điểm / Điểm lưu ý:</b>\n");
                for (JsonNode con : cons) {
                    sb.append("• ").append(escape(con.asText())).append("\n");
                }
                sb.append("\n");
            }

            // Khuyến nghị
            String recommendation = aiNode.path("recommendation").asText("");
            if (!recommendation.isBlank()) {
                sb.append("🎯 <b>Khuyến nghị:</b> <b>").append(escape(recommendation)).append("</b>\n");
            }
        }

        if (product.getAiSuggestedPrice() != null) {
            sb.append("\n💡 <b>Mức giá cạnh tranh gợi ý:</b> <b>")
                    .append(formatPrice(product.getAiSuggestedPrice().longValue()))
                    .append(" VNĐ</b>\n");
        }

        // ── LINK SÀN ────────────────────────────────────────────────────────
        String platform = "Sàn TMĐT";
        String platformEmoji = "🔗";
        if (product.getSource() != null) {
            switch (product.getSource()) {
                case SHOPEE -> { platform = "Shopee"; platformEmoji = "🟠"; }
                case LAZADA -> { platform = "Lazada"; platformEmoji = "🔵"; }
                case TAOBAO -> { platform = "Taobao"; platformEmoji = "🔴"; }
            }
        } else if (product.getProductUrl() != null) {
            if (product.getProductUrl().contains("lazada")) { platform = "Lazada"; platformEmoji = "🔵"; }
            else if (product.getProductUrl().contains("shopee")) { platform = "Shopee"; platformEmoji = "🟠"; }
        }

        if (product.getProductUrl() != null) {
            sb.append("\n").append(platformEmoji)
                    .append(" <a href=\"").append(product.getProductUrl())
                    .append("\">Xem trên ").append(platform).append("</a>\n");
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

    private String formatCount(long value) {
        if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format("%.1fK", value / 1_000.0);
        return String.valueOf(value);
    }
}
