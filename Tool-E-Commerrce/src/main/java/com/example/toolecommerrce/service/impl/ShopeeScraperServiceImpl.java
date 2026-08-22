package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.entity.Product;
import com.example.toolecommerrce.service.ShopeeScraperService;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
public class ShopeeScraperServiceImpl implements ShopeeScraperService {

    @Value("${scraper.shopee.headless:true}")
    private boolean headless;

    @Value("${scraper.shopee.page-load-timeout-seconds:30}")
    private int pageLoadTimeout;

    @Value("${scraper.shopee.implicit-wait-seconds:10}")
    private int implicitWait;

    @Value("${scraper.shopee.max-products-default:10}")
    private int maxProductsDefault;

    @Override
    public Product scrapeByUrl(String url) {
        ChromeDriver driver = createDriver();
        try {
            log.info("[Shopee] Scraping URL: {}", url);
            driver.get(url);
            randomDelay(2000, 4000);
            return extractProductDetail(driver, url);
        } catch (Exception e) {
            log.error("[Shopee] Failed to scrape URL {}: {}", url, e.getMessage());
            throw new RuntimeException("Scrape failed for URL: " + url, e);
        } finally {
            driver.quit();
        }
    }

    @Override
    public List<String> searchProductUrls(String keyword, int maxProducts) {
        ChromeDriver driver = createDriver();
        List<String> urls = new ArrayList<>();
        int limit = maxProducts > 0 ? maxProducts : maxProductsDefault;

        try {
            String searchUrl = "https://shopee.vn/search?keyword=" + encodeKeyword(keyword);
            log.info("[Shopee] Searching keyword='{}' limit={}", keyword, limit);
            driver.get(searchUrl);
            randomDelay(3000, 5000);

            scrollToLoadMore(driver, 3);
            randomDelay(2000, 3000);

            List<WebElement> productLinks = driver.findElements(
                    By.cssSelector("a[href*='/product/']")
            );

            for (WebElement link : productLinks) {
                if (urls.size() >= limit) break;
                String href = link.getAttribute("href");
                if (href != null && href.contains("shopee.vn") && !urls.contains(href)) {
                    urls.add(href);
                }
            }

            log.info("[Shopee] Found {} product URLs for keyword '{}'", urls.size(), keyword);
        } catch (Exception e) {
            log.error("[Shopee] Failed to search keyword {}: {}", keyword, e.getMessage());
        } finally {
            driver.quit();
        }
        return urls;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Product extractProductDetail(ChromeDriver driver, String url) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(implicitWait));

        Product.ProductBuilder builder = Product.builder()
                .productUrl(url)
                .source(Product.ProductSource.SHOPEE)
                .status(Product.ProductStatus.COMPLETED);

        // --- Tên sản phẩm ---
        try {
            WebElement nameEl = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("h1[class*='product-name'], span[class*='product-name'], .I5HM5j, ._44qnta")
            ));
            builder.name(nameEl.getText().trim());
        } catch (Exception e) {
            log.warn("[Shopee] Could not get product name");
            builder.name("N/A");
        }

        // --- Giá bán ---
        try {
            WebElement priceEl = driver.findElement(
                    By.cssSelector(".pmmxKx, [class*='current-price'], ._3n5NQx, .vip2Lk")
            );
            String priceText = priceEl.getText().replaceAll("[^\\d]", "");
            if (!priceText.isEmpty()) builder.price(new BigDecimal(priceText));
        } catch (Exception ignored) { log.warn("[Shopee] Could not get price"); }

        // --- Giá gốc ---
        try {
            WebElement origPriceEl = driver.findElement(
                    By.cssSelector("._2Shl1j, [class*='original-price']")
            );
            String origPriceText = origPriceEl.getText().replaceAll("[^\\d]", "");
            if (!origPriceText.isEmpty()) builder.originalPrice(new BigDecimal(origPriceText));
        } catch (Exception ignored) {}

        // --- Discount ---
        try {
            WebElement discountEl = driver.findElement(By.cssSelector("[class*='discount'], .percent-off"));
            String discText = discountEl.getText().replaceAll("[^\\d]", "");
            if (!discText.isEmpty()) builder.discount(Integer.parseInt(discText));
        } catch (Exception ignored) {}

        // --- Rating ---
        try {
            WebElement ratingEl = driver.findElement(By.cssSelector("._3Oj5_n, [class*='rating-score']"));
            String ratingText = ratingEl.getText().replaceAll("[^\\d.]", "");
            if (!ratingText.isEmpty()) builder.rating(Double.parseDouble(ratingText));
        } catch (Exception ignored) {}

        // --- Số lượng đã bán ---
        try {
            WebElement soldEl = driver.findElement(By.cssSelector("[class*='sold'], ._3OHOzt"));
            String soldText = soldEl.getText().replaceAll("[^\\d]", "");
            if (!soldText.isEmpty()) builder.soldCount(Long.parseLong(soldText));
        } catch (Exception ignored) {}

        // --- Tên shop ---
        try {
            WebElement shopEl = driver.findElement(By.cssSelector("[class*='shop-name'], .oVRd2v, .IFnZK5"));
            builder.shopName(shopEl.getText().trim());
        } catch (Exception ignored) {}

        // --- Hình ảnh chính ---
        try {
            WebElement imgEl = driver.findElement(
                    By.cssSelector("img[class*='product-image'], ._2hS26H img, .zoom-image")
            );
            String imgSrc = imgEl.getAttribute("src");
            if (imgSrc == null) imgSrc = imgEl.getAttribute("data-src");
            builder.imageUrl(imgSrc);
        } catch (Exception ignored) {}

        // --- Thông số kỹ thuật ---
        try {
            List<WebElement> specRows = driver.findElements(
                    By.cssSelector("[class*='specification'] tr, ._3-N5Sf tr, .UgNSAn tr")
            );
            StringBuilder specs = new StringBuilder();
            for (WebElement row : specRows) specs.append(row.getText().trim()).append("\n");
            if (!specs.isEmpty()) builder.specifications(specs.toString());
        } catch (Exception ignored) {}

        // --- Mô tả ---
        try {
            WebElement descEl = driver.findElement(By.cssSelector("[class*='product-desc'], ._2u0jt9, .yMblz_"));
            builder.description(descEl.getText().trim());
        } catch (Exception ignored) {}

        return builder.build();
    }

    private void scrollToLoadMore(ChromeDriver driver, int times) {
        JavascriptExecutor js = driver;
        for (int i = 0; i < times; i++) {
            js.executeScript("window.scrollBy(0, 800)");
            randomDelay(1000, 2000);
        }
    }

    private void randomDelay(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String encodeKeyword(String keyword) {
        return keyword.replace(" ", "%20");
    }

    private ChromeDriver createDriver() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        if (headless) options.addArguments("--headless=new");
        options.addArguments(
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1920,1080",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        );
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);
        return new ChromeDriver(options);
    }
}
