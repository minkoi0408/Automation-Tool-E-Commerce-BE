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

    @Value("${scraper.shopee.implicit-wait-seconds:15}")
    private int implicitWait;

    @Value("${scraper.shopee.max-products-default:10}")
    private int maxProductsDefault;

    @Value("${scraper.shopee.username:}")
    private String shopeeUsername;

    @Value("${scraper.shopee.password:}")
    private String shopeePassword;

    @Override
    public Product scrapeByUrl(String url) {
        ChromeDriver driver = createDriver();
        try {
            log.info("[Shopee] Scraping URL: {}", url);
            driver.get(url);
            randomDelay(4000, 6000);
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
        List<String> urls = new ArrayList<>();
        int limit = maxProducts > 0 ? maxProducts : maxProductsDefault;
        ChromeDriver driver = createDriver();

        try {
            log.info("[Shopee] Warming up session for keyword='{}'", keyword);

            // BƯỚC 1: Login Shopee (nếu có credentials)
            if (shopeeUsername != null && !shopeeUsername.isBlank()) {
                loginShopee(driver);
            } else {
                // Không có credentials: vào homepage lấy cookie
                driver.get("https://shopee.vn");
                randomDelay(3000, 5000);
            }

            // BƯỚC 2: Navigate đến search page (có cookie rồi)
            String encodedKeyword = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
            String searchUrl = "https://shopee.vn/search?keyword=" + encodedKeyword;
            log.info("[Shopee] Navigating to search: {}", searchUrl);
            driver.get(searchUrl);

            // BƯỚC 3: Chờ React render xong (Shopee dùng React)
            randomDelay(5000, 8000);

            // BƯỚC 4: Scroll để trigger lazy load
            scrollToLoadMore(driver, 5);
            randomDelay(2000, 3000);

            // BƯỚC 5: Check xem có bị block không
            String pageTitle = driver.getTitle();
            String pageSource = driver.getPageSource();
            log.info("[Shopee] Page title: {}", pageTitle);

            if (pageSource.contains("hết chỗ") || pageSource.contains("thử lại sau")) {
                log.warn("[Shopee] Rate limited! Waiting 10s and retrying...");
                randomDelay(10000, 15000);
                driver.navigate().refresh();
                randomDelay(5000, 7000);
                scrollToLoadMore(driver, 3);
            }

            // BƯỚC 6: Lấy tất cả link sản phẩm
            List<WebElement> allLinks = driver.findElements(By.tagName("a"));
            log.info("[Shopee] Total <a> tags found: {}", allLinks.size());

            for (WebElement link : allLinks) {
                if (urls.size() >= limit) break;
                try {
                    String href = link.getAttribute("href");
                    if (href != null
                            && href.contains("shopee.vn")
                            && href.matches(".*-i\\.\\d+\\.\\d+.*")
                            && !href.contains("/search")
                            && !href.contains("/mall")
                            && !urls.contains(href)) {
                        urls.add(href);
                        log.info("[Shopee] Found URL: {}", href);
                    }
                } catch (StaleElementReferenceException ignored) {}
            }

            // BƯỚC 7: Nếu vẫn 0 — thử lấy từ page source bằng regex
            if (urls.isEmpty()) {
                log.warn("[Shopee] No links found via DOM, trying regex on page source...");
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                        "\"(https://shopee\\.vn/[^\"]*-i\\.\\d+\\.\\d+[^\"]*)\"");
                java.util.regex.Matcher matcher = pattern.matcher(pageSource);
                while (matcher.find() && urls.size() < limit) {
                    String url = matcher.group(1);
                    if (!urls.contains(url)) {
                        urls.add(url);
                        log.info("[Shopee] Found URL via regex: {}", url);
                    }
                }
            }

            log.info("[Shopee] Total found {} URLs for keyword '{}'", urls.size(), keyword);

        } catch (Exception e) {
            log.error("[Shopee] Search failed for keyword {}: {}", keyword, e.getMessage());
        } finally {
            driver.quit();
        }
        return urls;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void loginShopee(ChromeDriver driver) {
        try {
            log.info("[Shopee] Logging in as {}", shopeeUsername);
            driver.get("https://shopee.vn/buyer/login");
            randomDelay(3000, 5000);

            // Chờ form login hiện ra
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

            // Điền username (phone/email)
            WebElement usernameField = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("input[name='loginKey'], input[type='text']:not([readonly])")));
            usernameField.clear();
            typeSlowly(usernameField, shopeeUsername);
            randomDelay(500, 1000);

            // Điền password
            WebElement passwordField = driver.findElement(
                    By.cssSelector("input[name='password'], input[type='password']"));
            passwordField.clear();
            typeSlowly(passwordField, shopeePassword);
            randomDelay(500, 1000);

            // Click đăng nhập
            WebElement loginBtn = driver.findElement(
                    By.cssSelector("button[type='submit'], .btn-solid-primary"));
            loginBtn.click();

            // Chờ redirect sau login (max 20s)
            randomDelay(5000, 8000);

            String currentUrl = driver.getCurrentUrl();
            if (currentUrl.contains("login") || currentUrl.contains("verify")) {
                log.warn("[Shopee] Login may require OTP verification - check browser window!");
                // Chờ user nhập OTP thủ công nếu cần
                randomDelay(15000, 20000);
            }

            log.info("[Shopee] Login done, current URL: {}", driver.getCurrentUrl());
        } catch (Exception e) {
            log.error("[Shopee] Login failed: {}", e.getMessage());
        }
    }

    private void typeSlowly(WebElement element, String text) throws InterruptedException {
        for (char c : text.toCharArray()) {
            element.sendKeys(String.valueOf(c));
            Thread.sleep(50 + (long)(Math.random() * 100));
        }
    }

    private Product extractProductDetail(ChromeDriver driver, String url) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(implicitWait));

        Product.ProductBuilder builder = Product.builder()
                .productUrl(url)
                .source(Product.ProductSource.SHOPEE)
                .status(Product.ProductStatus.COMPLETED);

        // === TÊN SẢN PHẨM ===
        // Shopee hiển thị tên trong h1 hoặc div với data attribute
        try {
            WebElement nameEl = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("h1, [data-sqe='name'] span, [class*='product-name']")
            ));
            String name = nameEl.getText().trim();
            if (name.isEmpty()) {
                // Thử lấy qua JavaScript
                name = (String) ((JavascriptExecutor) driver)
                        .executeScript("return document.querySelector('h1') ? document.querySelector('h1').innerText : ''");
            }
            builder.name(name != null && !name.isEmpty() ? name : "N/A");
        } catch (Exception e) {
            log.warn("[Shopee] Could not get product name, trying JS fallback");
            try {
                String name = (String) ((JavascriptExecutor) driver)
                        .executeScript("return document.querySelector('h1') ? document.querySelector('h1').innerText : 'N/A'");
                builder.name(name);
            } catch (Exception ex) {
                builder.name("N/A");
            }
        }

        // === GIÁ BÁN ===
        try {
            // Shopee giá thường nằm trong div/span có aria-label hoặc class price
            String priceJs = (String) ((JavascriptExecutor) driver).executeScript("""
                    var priceEl = document.querySelector('[class*="price"] .BdC28Y, [class*="price--current"], [class*="current_price"]');
                    if (!priceEl) {
                        var elements = document.querySelectorAll('[class*="price"]');
                        for (var el of elements) {
                            if (el.children.length === 0 && el.innerText.includes('₫')) {
                                priceEl = el; break;
                            }
                        }
                    }
                    return priceEl ? priceEl.innerText : '';
                    """);
            if (priceJs != null && !priceJs.isEmpty()) {
                String priceText = priceJs.replaceAll("[^\\d]", "");
                if (!priceText.isEmpty()) builder.price(new BigDecimal(priceText));
            }
        } catch (Exception ignored) {
            log.warn("[Shopee] Could not get price");
        }

        // === RATING ===
        try {
            String ratingJs = (String) ((JavascriptExecutor) driver).executeScript("""
                    var el = document.querySelector('[class*="rating"] [class*="score"], [class*="shopee-rating-stars__number"]');
                    return el ? el.innerText : '';
                    """);
            if (ratingJs != null && !ratingJs.isEmpty()) {
                String ratingText = ratingJs.replaceAll("[^\\d.]", "");
                if (!ratingText.isEmpty()) builder.rating(Double.parseDouble(ratingText));
            }
        } catch (Exception ignored) {}

        // === SỐ LƯỢNG ĐÃ BÁN ===
        try {
            String soldJs = (String) ((JavascriptExecutor) driver).executeScript("""
                    var elements = document.querySelectorAll('[class*="sold"]');
                    for (var el of elements) {
                        if (el.innerText && el.innerText.match(/\\d/)) return el.innerText;
                    }
                    return '';
                    """);
            if (soldJs != null && !soldJs.isEmpty()) {
                String soldText = soldJs.replaceAll("[^\\d]", "");
                if (!soldText.isEmpty()) builder.soldCount(Long.parseLong(soldText));
            }
        } catch (Exception ignored) {}

        // === TÊN SHOP ===
        try {
            String shopJs = (String) ((JavascriptExecutor) driver).executeScript("""
                    var el = document.querySelector('[class*="shop-name"], [data-sqe="shopName"]');
                    return el ? el.innerText : '';
                    """);
            if (shopJs != null && !shopJs.isEmpty()) builder.shopName(shopJs.trim());
        } catch (Exception ignored) {}

        // === HÌNH ẢNH ===
        try {
            String imgJs = (String) ((JavascriptExecutor) driver).executeScript("""
                    var img = document.querySelector('[class*="product-image"] img, [class*="main-image"] img');
                    if (!img) img = document.querySelector('img[src*="shopee"]');
                    return img ? (img.src || img.getAttribute('data-src')) : '';
                    """);
            if (imgJs != null && !imgJs.isEmpty()) builder.imageUrl(imgJs);
        } catch (Exception ignored) {}

        // === MÔ TẢ ===
        try {
            String descJs = (String) ((JavascriptExecutor) driver).executeScript("""
                    var el = document.querySelector('[class*="product-detail"], [class*="description"]');
                    return el ? el.innerText.substring(0, 2000) : '';
                    """);
            if (descJs != null && !descJs.isEmpty()) builder.description(descJs.trim());
        } catch (Exception ignored) {}

        return builder.build();
    }

    private void scrollToLoadMore(ChromeDriver driver, int times) {
        JavascriptExecutor js = driver;
        for (int i = 0; i < times; i++) {
            js.executeScript("window.scrollBy(0, window.innerHeight)");
            randomDelay(1500, 2500);
        }
        // Scroll về đầu để load hết
        js.executeScript("window.scrollTo(0, 0)");
        randomDelay(1000, 1500);
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

        // Dùng Chrome profile thật → đã login Shopee sẵn
        // QUAN TRỌNG: Chrome phải được đóng trước khi chạy!
        String userDataDir = System.getProperty("user.home")
                + "\\AppData\\Local\\Google\\Chrome\\User Data";

        options.addArguments(
                "--user-data-dir=" + userDataDir,
                "--profile-directory=Default",
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1920,1080",
                "--lang=vi-VN",
                "--disable-extensions",
                "--start-maximized",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        );
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        ChromeDriver driver;
        try {
            driver = new ChromeDriver(options);
            log.info("[Shopee] Chrome opened with user profile (logged-in session)");
        } catch (Exception e) {
            log.warn("[Shopee] Cannot use profile (Chrome may be open), falling back to fresh session: {}", e.getMessage());
            // Fallback: fresh session không có profile
            ChromeOptions fallback = new ChromeOptions();
            if (headless) fallback.addArguments("--headless=new");
            fallback.addArguments("--disable-blink-features=AutomationControlled",
                    "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
                    "--window-size=1920,1080", "--lang=vi-VN", "--start-maximized");
            fallback.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
            fallback.setExperimentalOption("useAutomationExtension", false);
            driver = new ChromeDriver(fallback);
        }

        // CDP: remove webdriver fingerprint
        driver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                java.util.Map.of("source", """
                    Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                    Object.defineProperty(navigator, 'languages', {get: () => ['vi-VN', 'vi', 'en-US', 'en']});
                    Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});
                    window.chrome = { runtime: {} };
                """));

        return driver;
    }
}
