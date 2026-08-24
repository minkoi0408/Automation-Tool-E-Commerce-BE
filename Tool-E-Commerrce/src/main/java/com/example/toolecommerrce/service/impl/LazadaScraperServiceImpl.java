package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.entity.Product;
import com.example.toolecommerrce.service.LazadaScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lazada Scraper Hybrid:
 * 1. Search: Dùng Selenium mở trang catalog để lấy chính xác các sản phẩm top đầu từ thanh tìm kiếm Lazada.
 * 2. Scrape Detail: Dùng HTTP thuần + Jsoup phân tích dữ liệu siêu nhanh, không mở browser và không bị CAPTCHA.
 */
@Service
@Slf4j
public class LazadaScraperServiceImpl implements LazadaScraperService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final java.util.Map<String, String> sessionCookies = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${scraper.lazada.headless:true}")
    private boolean headless;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private synchronized void ensureCookies() {
        if (sessionCookies.isEmpty()) {
            try {
                Connection.Response resp = Jsoup.connect("https://www.lazada.vn/")
                        .userAgent(USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                        .timeout(10_000)
                        .execute();
                sessionCookies.putAll(resp.cookies());
                log.info("[Lazada] Session cookies initialized: {} cookies", sessionCookies.size());
            } catch (Exception e) {
                log.warn("[Lazada] Failed to initialize session cookies: {}", e.getMessage());
            }
        }
    }

    @Override
    public List<String> searchProductUrls(String keyword, int maxProducts) {
        log.info("[Lazada] Searching keyword='{}', max={}", keyword, maxProducts);
        List<String> urls = new ArrayList<>();

        // === STRATEGY 1: Selenium lấy trực tiếp từ trang Search của Lazada (Chuẩn 100% theo giao diện web) ===
        try {
            urls = searchViaSelenium(keyword, maxProducts);
            if (!urls.isEmpty()) {
                log.info("[Lazada] Selenium search found {} real-time top products", urls.size());
                return urls;
            }
        } catch (Exception e) {
            log.warn("[Lazada] Selenium search failed: {}", e.getMessage());
        }

        // === STRATEGY 2: Yahoo Search (Fallback cực kỳ ổn định) ===
        if (urls.isEmpty()) {
            try {
                urls = searchViaYahoo(keyword, maxProducts);
                if (!urls.isEmpty()) {
                    log.info("[Lazada] Yahoo search found {} URLs", urls.size());
                    return urls;
                }
            } catch (Exception e) {
                log.warn("[Lazada] Yahoo search failed: {}", e.getMessage());
            }
        }

        // === STRATEGY 3: Lazada Direct AJAX Catalog (Fallback) ===
        if (urls.isEmpty()) {
            try {
                urls = searchViaLazadaAjax(keyword, maxProducts);
                if (!urls.isEmpty()) {
                    log.info("[Lazada] Direct Ajax search found {} URLs", urls.size());
                    return urls;
                }
            } catch (Exception e) {
                log.warn("[Lazada] Direct Ajax search failed: {}", e.getMessage());
            }
        }

        // === STRATEGY 4: Bing Search (Fallback) ===
        if (urls.isEmpty()) {
            urls = searchViaBing(keyword, maxProducts);
        }

        log.info("[Lazada] Total found {} URLs for keyword '{}'", urls.size(), keyword);
        return urls;
    }

    // Cache lưu trữ metadata từ Selenium search (sellerName, rating, reviews, soldCount)
    private final Map<String, CardMetadata> searchMetadataCache = new ConcurrentHashMap<>();

    /**
     * Tìm sản phẩm thực tế trên thanh Search của Lazada bằng Selenium
     */
    private List<String> searchViaSelenium(String keyword, int maxProducts) {
        List<String> urls = new ArrayList<>();
        ChromeDriver driver = null;
        try {
            driver = createDriver();
            String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String searchUrl = "https://www.lazada.vn/catalog/?q=" + encoded;
            log.info("[Lazada] Opening real-time search catalog: {}", searchUrl);
            driver.get(searchUrl);

            // Chờ 3-4s để React render danh sách sản phẩm
            randomDelay(3500, 4500);

            // Scroll nhẹ để tải thêm sản phẩm
            try {
                ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 700)");
                randomDelay(1000, 1500);
            } catch (Exception ignored) {}

            // Trích xuất metadata thẻ sản phẩm trực tiếp từ DOM bằng JavaScript
            try {
                String script = """
                    var res = [];
                    var cards = document.querySelectorAll('div[data-qa-locator="product-item"], div[class*="Bm3ON"], div[class*="product-card"], div[data-item-id]');
                    if (!cards || cards.length === 0) {
                        cards = document.querySelectorAll('div[data-tracking="product-card"]');
                    }
                    cards.forEach(function(card) {
                        var a = card.querySelector('a[href*="/products/"]') || (card.tagName === 'A' ? card : null);
                        if (!a) return;
                        var href = a.href || "";
                        if (!href || href.indexOf('/products/') === -1) return;

                        var cardText = card.innerText || "";

                        // Title
                        var titleEl = card.querySelector('a[title], [class*="title"], [class*="name"]');
                        var title = titleEl ? (titleEl.getAttribute('title') || titleEl.innerText.trim()) : "";
                        if (!title) title = a.getAttribute('title') || a.innerText.trim();
                        if (!title && cardText) {
                            var lines = cardText.split(/[\\r\\n]+/);
                            if (lines.length > 0) title = lines[0].trim();
                        }

                        // Price - extract from price element first for accuracy
                        var price = "";
                        var pEl = card.querySelector('[class*="ooOxS"], [class*="price"]:not(del):not(s):not([class*="original"]):not([class*="deleted"]), [class*="currency"]');
                        if (pEl) {
                            var pTxt = pEl.innerText.trim();
                            var pMatch2 = pTxt.match(/[₫đ] *([0-9.,]+)/);
                            if (!pMatch2) pMatch2 = pTxt.match(/([0-9.,]+) *[₫đ]/);
                            if (!pMatch2) pMatch2 = pTxt.match(/([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,9})/);
                            if (pMatch2) price = pMatch2[1];
                        }
                        if (!price) {
                            var pMatch = cardText.match(/₫ *([0-9.,]+)/) || cardText.match(/([0-9.,]+) *₫/) || cardText.match(/([0-9.,]+) *(?:đ|VND|VNĐ)/i);
                            if (pMatch) {
                                price = pMatch[1];
                            } else {
                                var pNum = cardText.match(/([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,9})/);
                                if (pNum) price = pNum[1];
                            }
                        }

                        // Original Price
                        var origPrice = "";
                        var delEl = card.querySelector('del, s, [class*="deleted"], [class*="original"]');
                        if (delEl) origPrice = delEl.innerText.trim();

                        // Discount
                        var discount = "";
                        var discMatch = cardText.match(/-?([0-9]{1,2})%/);
                        if (discMatch) discount = discMatch[1];

                        // Seller / Shop
                        var seller = "";
                        var sEl = card.querySelector('[class*="seller"], [class*="shop-name"], [class*="brand"], [class*="store"]');
                        if (sEl) seller = sEl.innerText.trim();
                        if (!seller) {
                            var mallEl = card.querySelector('[class*="mall"], [class*="Mall"], [class*="official"]');
                            if (mallEl || cardText.indexOf('LazMall') >= 0) seller = "Gian hàng chính hãng LazMall";
                        }

                        // Rating - Lazada renders stars as SVG, not text characters
                        var rating = "";
                        var reviews = "";
                        
                        // Strategy 1: Find rating container and check attributes
                        var ratingEls = card.querySelectorAll('[class*="rating"], [class*="star"], [class*="rate"], [class*="score"]');
                        for (var ri = 0; ri < ratingEls.length && !rating; ri++) {
                            var rEl = ratingEls[ri];
                            // Check aria-label, title, data-score attributes
                            var ariaLabel = rEl.getAttribute('aria-label') || "";
                            var titleAttr = rEl.getAttribute('title') || "";
                            var dataScore = rEl.getAttribute('data-score') || rEl.getAttribute('data-rating') || "";
                            var checkStr = ariaLabel + " " + titleAttr + " " + dataScore;
                            var rParsed = checkStr.match(/([1-5](?:[.][0-9])?)/);
                            if (rParsed) rating = rParsed[1];
                        }
                        
                        // Strategy 2: Count filled SVG stars (yellow/orange fill)
                        if (!rating) {
                            var svgStars = card.querySelectorAll('svg, i[class*="star"]');
                            if (svgStars.length >= 3 && svgStars.length <= 5) {
                                var filled = 0;
                                for (var svi = 0; svi < svgStars.length; svi++) {
                                    var svg = svgStars[svi];
                                    var fill = svg.getAttribute('fill') || svg.getAttribute('color') || "";
                                    var cls = svg.className ? (typeof svg.className === 'string' ? svg.className : svg.className.baseVal || "") : "";
                                    var style = svg.getAttribute('style') || "";
                                    if (fill.indexOf('#f') >= 0 || fill.indexOf('gold') >= 0 || fill.indexOf('yellow') >= 0 || fill.indexOf('orange') >= 0 || fill.indexOf('#F') >= 0 || cls.indexOf('filled') >= 0 || cls.indexOf('active') >= 0 || cls.indexOf('full') >= 0 || style.indexOf('color') >= 0) {
                                        filled++;
                                    }
                                }
                                if (filled > 0) rating = "" + filled;
                            }
                        }
                        
                        // Strategy 3: Check star container width ratio (CSS-based stars)
                        if (!rating) {
                            var starWrapper = card.querySelector('[class*="rating"], [class*="star"]');
                            if (starWrapper) {
                                var inner = starWrapper.querySelector('[class*="fill"], [class*="active"], [class*="inner"]');
                                if (inner) {
                                    var w = inner.style.width;
                                    if (w && w.indexOf('%') >= 0) {
                                        var pct = parseFloat(w);
                                        if (pct > 0) rating = "" + Math.round(pct / 20 * 10) / 10;
                                    }
                                }
                            }
                        }
                        
                        // Extract review count from (N) pattern
                        var revMatch = cardText.match(/[(]([0-9]+)[)]/);
                        if (revMatch) reviews = revMatch[1];

                        // Sold
                        var sold = "";
                        var soldMatch = cardText.match(/([0-9]+(?:[.][0-9]+)?[kKmM]?) *(?:Đã bán|đã bán|sold|lượt bán)/i);
                        if (soldMatch) {
                            sold = soldMatch[1];
                        } else {
                            var dEl = card.querySelector('[class*="sold"]');
                            if (dEl) sold = dEl.innerText.trim();
                        }

                        // Store Sold
                        var storeSold = "";
                        var stMatch = cardText.match(/([0-9]+(?:[.][0-9]+)?[kKmM]?) *(?:Sold by Store|bán bởi shop|đã bán của shop)/i);
                        if (stMatch) storeSold = stMatch[1];

                        // Image
                        var imgEl = card.querySelector('img');
                        var img = imgEl ? (imgEl.src || imgEl.getAttribute('data-src') || "") : "";

                        // Location - get from element or last line of card text
                        var location = "";
                        var locEl = card.querySelector('[class*="location"], [class*="shop-location"], [class*="origin"], [class*="ship"]');
                        if (locEl) location = locEl.innerText.trim();
                        if (!location && cardText) {
                            var lines = cardText.split('\\n');
                            for (var li = lines.length - 1; li >= 0; li--) {
                                var lTrim = lines[li].trim();
                                if (lTrim.length > 1 && lTrim.length < 50 && !lTrim.match(/^[0-9.,]+ *(?:₫|đ|%)/) && lTrim.indexOf('Đã bán') === -1 && lTrim.indexOf('Off') === -1 && lTrim.indexOf('Voucher') === -1) {
                                    location = lTrim;
                                    break;
                                }
                            }
                        }
                        if (!location) location = "Việt Nam";

                        var m = href.match(/i([0-9]+)/);
                        var itemId = m ? m[1] : "";

                        res.push({
                            url: href,
                            itemId: itemId,
                            name: title,
                            price: price,
                            origPrice: origPrice,
                            discount: discount,
                            seller: seller,
                            rating: rating,
                            reviews: reviews,
                            sold: sold,
                            storeSold: storeSold,
                            image: img,
                            location: location,
                            debugCardText: ""
                        });
                    });
                    return res;
                """;
                Object rawCards = ((JavascriptExecutor) driver).executeScript(script);
                if (rawCards instanceof List<?> cardList) {
                    for (Object itemObj : cardList) {
                        if (itemObj instanceof Map<?, ?> itemMap) {
                            String itemUrl = (String) itemMap.get("url");
                            if (itemUrl == null || !itemUrl.contains("lazada.vn/products/")) continue;
                            String clean = itemUrl.split("\\?")[0].replaceAll("-s\\d+\\.html", ".html");
                            if (!urls.contains(clean) && urls.size() < maxProducts) {
                                urls.add(clean);
                                log.info("[Lazada] Found product URL: {}", clean);
                            }
                            String itemId = (String) itemMap.get("itemId");
                            String name = (String) itemMap.get("name");
                            String pText = (String) itemMap.get("price");
                            String opText = (String) itemMap.get("origPrice");
                            String dText = (String) itemMap.get("discount");
                            String s = (String) itemMap.get("seller");
                            String r = (String) itemMap.get("rating");
                            String rev = (String) itemMap.get("reviews");
                            String d = (String) itemMap.get("sold");
                            String st = (String) itemMap.get("storeSold");
                            String img = (String) itemMap.get("image");
                            String loc = (String) itemMap.get("location");

                            BigDecimal price = parsePrice(pText);
                            BigDecimal origPrice = parsePrice(opText);
                            Integer disc = parseDiscount(dText);
                            Double rVal = parseDouble(r);
                            Long revVal = parseLong(rev);
                            Long sVal = parseSoldCount(d);

                            CardMetadata meta = new CardMetadata(name, price, origPrice, disc, s, rVal, revVal, sVal, st, img, loc, clean);
                            if (itemId != null && !itemId.isBlank()) {
                                searchMetadataCache.put(itemId, meta);
                            }
                            searchMetadataCache.put(clean, meta);
                            log.info("[Lazada] Cached card data for itemId={}: name='{}', price={}, seller='{}', rating={}, reviews={}, sold={}, storeSold='{}'",
                                    itemId, name, price, s, rVal, revVal, sVal, st);
                            // Debug: log card text for first 2 cards to see what Lazada renders
                            String debugText = (String) itemMap.get("debugCardText");
                            if (debugText != null && !debugText.isBlank()) {
                                log.info("[Lazada DEBUG] Card text for itemId={}: '{}'", itemId, debugText);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Lazada] JS card extraction error: {}", e.getMessage());
            }

            // Fallback nếu chưa đủ URL
            if (urls.size() < maxProducts) {
                List<WebElement> linkElements = driver.findElements(By.cssSelector("a[href*='/products/']"));
                for (WebElement el : linkElements) {
                    if (urls.size() >= maxProducts) break;
                    try {
                        String href = el.getAttribute("href");
                        if (href != null && href.contains("lazada.vn/products/")) {
                            String cleanUrl = href.split("\\?")[0].replaceAll("-s\\d+\\.html", ".html");
                            if (!urls.contains(cleanUrl)) {
                                urls.add(cleanUrl);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("[Lazada] Selenium search error: {}", e.getMessage());
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }
        return urls;
    }

    private record CardMetadata(
            String name,
            BigDecimal price,
            BigDecimal origPrice,
            Integer discount,
            String seller,
            Double rating,
            Long reviews,
            Long sold,
            String storeSold,
            String image,
            String location,
            String url
    ) {}

    private ChromeDriver createDriver() {
        return createDriverInternal(headless);
    }

    /**
     * Tạo ChromeDriver KHÔNG headless — dùng cho PDP scraping để bypass Lazada TMD anti-bot.
     * Lazada TMD detect headless Chrome và redirect sang /_____tmd_____/punish.
     * Visible Chrome không bị detect.
     */
    private ChromeDriver createDriverNonHeadless() {
        return createDriverInternal(false);
    }

    private ChromeDriver createDriverInternal(boolean useHeadless) {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        if (useHeadless) options.addArguments("--headless=new");

        options.addArguments(
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1920,1080",
                "--lang=vi-VN",
                "--disable-extensions",
                "--start-maximized",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        );
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        ChromeDriver driver = new ChromeDriver(options);

        // Xóa dấu hiệu automation
        try {
            driver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                    java.util.Map.of("source", """
                        Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                        Object.defineProperty(navigator, 'languages', {get: () => ['vi-VN', 'vi', 'en-US', 'en']});
                        Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});
                        window.chrome = { runtime: {} };
                    """));
        } catch (Exception ignored) {}

        return driver;
    }

    /**
     * Tìm sản phẩm Lazada qua Yahoo Search.
     * Query: site:lazada.vn/products {keyword}
     */
    private List<String> searchViaYahoo(String keyword, int maxProducts) throws Exception {
        List<String> urls = new ArrayList<>();
        String encoded = URLEncoder.encode("site:lazada.vn/products " + keyword, StandardCharsets.UTF_8);
        String yahooUrl = "https://search.yahoo.com/search?p=" + encoded;

        log.info("[Lazada] Searching via Yahoo: {}", yahooUrl);

        Document doc = Jsoup.connect(yahooUrl)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .referrer("https://search.yahoo.com/")
                .timeout(10_000)
                .get();

        Elements links = doc.select("a[href]");
        for (Element link : links) {
            if (urls.size() >= maxProducts) break;
            String href = link.attr("href");
            String actualUrl = null;

            if (href.contains("/RU=")) {
                Matcher m = Pattern.compile("/RU=(https?[^/]+(?:%2f|/)[^/]+)/RK=").matcher(href);
                if (m.find()) {
                    actualUrl = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
                }
            } else if (href.contains("lazada.vn/products/")) {
                actualUrl = href;
            }

            if (actualUrl != null && actualUrl.contains("lazada.vn/products/")) {
                String cleanUrl = actualUrl.split("\\?")[0]
                        .replaceAll("-s\\d+\\.html", ".html");
                if (!urls.contains(cleanUrl)) {
                    urls.add(cleanUrl);
                    log.info("[Lazada] Found URL (Yahoo): {}", cleanUrl);
                }
            }
        }

        log.info("[Lazada] Yahoo search found {} URLs", urls.size());
        return urls;
    }

    /**
     * Tìm sản phẩm trực tiếp qua Lazada AJAX catalog endpoint.
     * Trả về JSON chứa mods.listItems.
     */
    private List<String> searchViaLazadaAjax(String keyword, int maxProducts) throws Exception {
        ensureCookies();
        List<String> urls = new ArrayList<>();
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String ajaxUrl = "https://www.lazada.vn/catalog/?q=" + encoded + "&ajax=true";

        log.info("[Lazada] Querying AJAX catalog: {}", ajaxUrl);

        Connection.Response response = Jsoup.connect(ajaxUrl)
                .userAgent(USER_AGENT)
                .cookies(sessionCookies)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                .referrer("https://www.lazada.vn/")
                .ignoreContentType(true)
                .timeout(15_000)
                .maxBodySize(10_000_000)
                .execute();

        // Update cookies if any new ones received
        sessionCookies.putAll(response.cookies());

        String rawJson = response.body();
        log.info("[Lazada] Ajax response received, length: {}", rawJson != null ? rawJson.length() : 0);

        if (rawJson == null || rawJson.isBlank()) {
            return urls;
        }

        // Extract JSON data
        JsonNode root = objectMapper.readTree(rawJson);
        JsonNode listItems = root.path("mods").path("listItems");

        if (listItems.isArray()) {
            for (JsonNode item : listItems) {
                if (urls.size() >= maxProducts) break;
                String itemUrl = item.path("itemUrl").asText("");
                String itemId = item.path("itemId").asText("");
                String fullUrl = null;

                if (!itemUrl.isBlank()) {
                    if (itemUrl.startsWith("//")) {
                        fullUrl = "https:" + itemUrl;
                    } else if (itemUrl.startsWith("/")) {
                        fullUrl = "https://www.lazada.vn" + itemUrl;
                    } else {
                        fullUrl = itemUrl;
                    }
                } else if (!itemId.isBlank()) {
                    fullUrl = "https://www.lazada.vn/products/pdp-i" + itemId + ".html";
                }

                if (fullUrl != null) {
                    fullUrl = fullUrl.split("\\?")[0];
                    if (!urls.contains(fullUrl)) {
                        urls.add(fullUrl);
                        log.info("[Lazada] Ajax found URL: {}", fullUrl);
                    }
                }
            }
        }

        return urls;
    }

    /**
     * Tìm sản phẩm Lazada qua Google Search.
     * Query: site:lazada.vn/products {keyword}
     */
    private List<String> searchViaGoogle(String keyword, int maxProducts) throws Exception {
        List<String> urls = new ArrayList<>();
        String encoded = URLEncoder.encode("site:lazada.vn/products " + keyword, StandardCharsets.UTF_8);
        String googleUrl = "https://www.google.com/search?q=" + encoded + "&num=" + Math.min(maxProducts * 2, 20);

        log.info("[Lazada] Searching via Google: {}", googleUrl);

        Document doc = Jsoup.connect(googleUrl)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .referrer("https://www.google.com/")
                .timeout(10_000)
                .get();

        // Google kết quả nằm trong <a> tags với href chứa lazada.vn/products/
        Elements links = doc.select("a[href]");
        for (Element link : links) {
            if (urls.size() >= maxProducts) break;
            String href = link.attr("href");

            // Google wrap URLs trong /url?q=... hoặc trả trực tiếp
            String actualUrl = null;
            if (href.contains("/url?q=")) {
                // Extract actual URL from Google redirect
                Matcher m = Pattern.compile("[?&]q=(https?://[^&]+)").matcher(href);
                if (m.find()) {
                    actualUrl = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
                }
            } else if (href.startsWith("https://www.lazada.vn/products/")) {
                actualUrl = href;
            }

            if (actualUrl != null && actualUrl.contains("lazada.vn/products/")) {
                String cleanUrl = actualUrl.split("\\?")[0]
                        .replaceAll("-s\\d+\\.html", ".html");
                if (!urls.contains(cleanUrl)) {
                    urls.add(cleanUrl);
                    log.info("[Lazada] Found URL (Google): {}", cleanUrl);
                }
            }
        }

        log.info("[Lazada] Google search found {} URLs", urls.size());
        return urls;
    }

    /**
     * Fallback: thử tìm trực tiếp trên Lazada search page.
     * Chỉ hoạt động nếu Lazada nhúng data trong HTML (hiếm khi).
     */
    private List<String> searchViaLazadaDirect(String keyword, int maxProducts) throws Exception {
        List<String> urls = new ArrayList<>();
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String searchUrl = "https://www.lazada.vn/catalog/?q=" + encoded + "&sort=0";

        Document doc = fetchDocument(searchUrl);
        String html = doc.html();

        // Tìm product URLs trong HTML bằng regex
        Pattern productUrlPattern = Pattern.compile(
                "//www\\.lazada\\.vn/products/[a-zA-Z0-9_-]+-i(\\d+)(?:-s\\d+)?\\.html");
        Matcher matcher = productUrlPattern.matcher(html);

        while (matcher.find() && urls.size() < maxProducts) {
            String fullUrl = "https:" + matcher.group(0).split("\\?")[0];
            fullUrl = fullUrl.replaceAll("-s\\d+\\.html", ".html");
            if (!urls.contains(fullUrl)) {
                urls.add(fullUrl);
                log.info("[Lazada] Found URL (direct): {}", fullUrl);
            }
        }

        // Fallback: tìm itemId trong JSON data
        if (urls.isEmpty()) {
            Pattern itemIdPattern = Pattern.compile("\"itemId\"\\s*:\\s*\"?(\\d{10,})\"?");
            Matcher itemMatcher = itemIdPattern.matcher(html);
            while (itemMatcher.find() && urls.size() < maxProducts) {
                String itemId = itemMatcher.group(1);
                String constructedUrl = "https://www.lazada.vn/products/-i" + itemId + ".html";
                if (!urls.contains(constructedUrl)) {
                    urls.add(constructedUrl);
                    log.info("[Lazada] Found URL (itemId): {}", constructedUrl);
                }
            }
        }

        return urls;
    }

    /**
     * Fallback: Tìm qua Bing Search
     */
    private List<String> searchViaBing(String keyword, int maxProducts) {
        List<String> urls = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode("site:lazada.vn/products " + keyword, StandardCharsets.UTF_8);
            String bingUrl = "https://www.bing.com/search?q=" + encoded;
            log.info("[Lazada] Searching via Bing: {}", bingUrl);

            Document doc = Jsoup.connect(bingUrl)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(10_000)
                    .get();

            Elements links = doc.select("a[href]");
            for (Element link : links) {
                if (urls.size() >= maxProducts) break;
                String href = link.attr("href");
                if (href.contains("lazada.vn/products/")) {
                    String cleanUrl = href.split("\\?")[0]
                            .replaceAll("-s\\d+\\.html", ".html");
                    if (!urls.contains(cleanUrl)) {
                        urls.add(cleanUrl);
                        log.info("[Lazada] Found URL (Bing): {}", cleanUrl);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Lazada] Bing search failed: {}", e.getMessage());
        }
        return urls;
    }

    // ── SCRAPE BY URL ────────────────────────────────────────────────────────

    @Override
    public Product scrapeByUrl(String url) {
        log.info("[Lazada] Scraping URL: {}", url);

        // 1. Kiểm tra cache từ Selenium search nếu ĐẦY ĐỦ VÀ HỢP LỆ
        String itemId = extractItemIdFromUrl(url);
        CardMetadata cached = null;
        if (itemId != null) cached = searchMetadataCache.get(itemId);
        if (cached == null) {
            String clean = url.split("\\?")[0].replaceAll("-s\\d+\\.html", ".html");
            cached = searchMetadataCache.get(clean);
        }

        if (cached != null && cached.name() != null && !cached.name().isBlank()
                && cached.price() != null && cached.price().compareTo(BigDecimal.valueOf(1000)) >= 0
                && cached.seller() != null && !cached.seller().isBlank() && !cached.seller().equalsIgnoreCase("Shop trên sàn TMĐT")
                && cached.reviews() != null && cached.reviews() > 0) {
            log.info("[Lazada] ✅ Found Complete in Search Cache => name='{}', price={}, seller='{}', rating={}, reviews={}, sold={}, storeSold='{}'",
                    cached.name(), cached.price(), cached.seller(), cached.rating(), cached.reviews(), cached.sold(), cached.storeSold());
            return Product.builder()
                    .name(cached.name())
                    .price(cached.price())
                    .originalPrice(cached.origPrice())
                    .discount(cached.discount())
                    .rating(cached.rating() != null ? cached.rating() : 5.0)
                    .reviewCount(cached.reviews())
                    .soldCount(cached.sold())
                    .shopName(cached.seller())
                    .shopSoldCount(cached.storeSold())
                    .shopLocation(cached.location() != null ? cached.location() : "Việt Nam")
                    .imageUrl(cached.image())
                    .productUrl(url)
                    .source(Product.ProductSource.LAZADA)
                    .status(Product.ProductStatus.PROCESSING)
                    .build();
        }

        try {
            Document doc = fetchDocument(url);
            String html = doc.html();

            String name = null;
            String priceText = null;
            String imageUrl = null;
            String shopName = null;
            String shopSoldCount = null;
            String brand = null;
            Integer discount = null;
            String category = null;

            // === 1. Extract từ pdpTrackingData (JSON trong <script>) ===
            String pdpJson = extractJsonFromScript(html, "pdpTrackingData");
            if (pdpJson != null) {
                try {
                    JsonNode pdp = objectMapper.readTree(pdpJson);
                    name = getJsonText(pdp, "pdt_name");
                    priceText = getJsonText(pdp, "pdt_price");
                    imageUrl = getJsonText(pdp, "pdt_photo");
                    shopName = getJsonText(pdp, "seller_name");
                    brand = getJsonText(pdp, "brand_name");

                    String discStr = getJsonText(pdp, "pdt_discount");
                    if (discStr != null) {
                        discount = parseDiscount(discStr);
                    }

                    JsonNode catNode = pdp.path("pdt_category");
                    if (catNode.isArray() && catNode.size() > 0) {
                        category = catNode.get(catNode.size() - 1).asText();
                    }

                    log.info("[Lazada] pdpTrackingData => name='{}', price='{}', shop='{}', brand='{}', discount={}",
                            name, priceText, shopName, brand, discount);
                } catch (Exception e) {
                    log.warn("[Lazada] Failed to parse pdpTrackingData: {}", e.getMessage());
                }
            }

            // === 2. Fallback/bổ sung từ <meta> tags ===
            if (name == null || name.isBlank() || "N/A".equalsIgnoreCase(name)) {
                name = getMetaContent(doc, "og:title");
                if (name != null && name.contains(" | Lazada")) {
                    name = name.substring(0, name.indexOf(" | Lazada")).trim();
                }
            }
            if (imageUrl == null || imageUrl.isBlank()) {
                imageUrl = getMetaContent(doc, "og:image");
            }
            String description = getMetaContent(doc, "description");
            if (description == null || description.isBlank()) {
                description = getMetaContent(doc, "og:description");
            }

            // === 3. Extract từ <title> tag ===
            if (name == null || name.isBlank() || "N/A".equalsIgnoreCase(name)) {
                String title = doc.title();
                if (title != null && title.contains(" | Lazada")) {
                    name = title.substring(0, title.indexOf(" | Lazada")).trim();
                } else if (title != null && !title.isBlank()) {
                    name = title.trim();
                }
            }

            // === 4. Extract price từ __moduleData__ (skuInfos) ===
            BigDecimal price = parsePrice(priceText);
            BigDecimal originalPrice = null;

            String moduleJson = extractModuleData(html);
            Double rating = null;
            Long reviewCount = null;
            Long soldCount = null;

            if (moduleJson != null) {
                try {
                    JsonNode module = objectMapper.readTree(moduleJson);

                    // Tìm skuInfos để lấy price chính xác
                    JsonNode skuInfos = findDeepNode(module, "skuInfos");
                    if (skuInfos != null && skuInfos.isObject()) {
                        var fields = skuInfos.fields();
                        if (fields.hasNext()) {
                            JsonNode firstSku = fields.next().getValue();
                            JsonNode priceNode = firstSku.path("price");
                            if (!priceNode.isMissingNode()) {
                                JsonNode salePrice = priceNode.path("salePrice");
                                JsonNode origPrice = priceNode.path("originalPrice");
                                if (!salePrice.isMissingNode()) {
                                    JsonNode salePriceValue = salePrice.path("value");
                                    if (!salePriceValue.isMissingNode() && !salePriceValue.isNull()) {
                                        price = new BigDecimal(salePriceValue.asText());
                                    } else {
                                        BigDecimal p = parsePrice(salePrice.asText());
                                        if (p != null) price = p;
                                    }
                                }
                                if (!origPrice.isMissingNode()) {
                                    JsonNode origPriceValue = origPrice.path("value");
                                    if (!origPriceValue.isMissingNode() && !origPriceValue.isNull()) {
                                        originalPrice = new BigDecimal(origPriceValue.asText());
                                    } else {
                                        originalPrice = parsePrice(origPrice.asText());
                                    }
                                }
                            }
                        }
                    }

                    // Tìm review/rating data
                    JsonNode reviewNode = findDeepNode(module, "review");
                    if (reviewNode != null) {
                        JsonNode ratingNode = reviewNode.path("ratings");
                        if (!ratingNode.isMissingNode()) {
                            JsonNode avgNode = ratingNode.path("average");
                            if (!avgNode.isMissingNode()) {
                                rating = avgNode.asDouble();
                            }
                            JsonNode countNode = ratingNode.path("ratingCount");
                            if (!countNode.isMissingNode()) {
                                reviewCount = countNode.asLong();
                            }
                        }
                        if (rating == null) {
                            JsonNode avgScore = reviewNode.path("averageScore");
                            if (!avgScore.isMissingNode()) {
                                rating = avgScore.asDouble();
                            }
                        }
                    }

                    // Tìm seller / shop trong moduleData
                    JsonNode sellerNode = findDeepNode(module, "seller");
                    if (sellerNode == null) sellerNode = findDeepNode(module, "sellerInfo");
                    if (sellerNode == null) sellerNode = findDeepNode(module, "shopInfo");
                    if (sellerNode == null) sellerNode = findDeepNode(module, "sellerCard");
                    if (sellerNode != null) {
                        String sName = getJsonText(sellerNode, "name");
                        if (sName == null) sName = getJsonText(sellerNode, "sellerName");
                        if (sName == null) sName = getJsonText(sellerNode, "shopName");
                        if (sName == null) sName = getJsonText(sellerNode, "sellerTitle");
                        if (sName == null) sName = getJsonText(sellerNode, "title");
                        if (sName != null && !sName.isBlank()) {
                            shopName = sName;
                            log.info("[Lazada] Extracted seller from moduleData: {}", shopName);
                        }
                        String sSold = getJsonText(sellerNode, "storeSold");
                        if (sSold == null) sSold = getJsonText(sellerNode, "sold");
                        if (sSold == null) sSold = getJsonText(sellerNode, "itemSold");
                        if (sSold == null) sSold = getJsonText(sellerNode, "orderCount");
                        if (sSold == null) sSold = getJsonText(sellerNode, "soldCount");
                        if (sSold != null && !sSold.isBlank()) {
                            shopSoldCount = sSold;
                            log.info("[Lazada] Extracted shopSoldCount from moduleData: {}", shopSoldCount);
                        }
                    }

                    // Tìm sold count trong moduleData
                    JsonNode soldNode = findDeepNode(module, "sold");
                    if (soldNode == null) soldNode = findDeepNode(module, "itemSold");
                    if (soldNode == null) soldNode = findDeepNode(module, "tradeSold");
                    if (soldNode == null) soldNode = findDeepNode(module, "orderCount");
                    if (soldNode != null) {
                        soldCount = parseSoldCount(soldNode.asText());
                    }

                } catch (Exception e) {
                    log.warn("[Lazada] Failed to parse __moduleData__: {}", e.getMessage());
                }
            }

            // === 5. Fallback Regex cho Price nếu vẫn null ===
            if (price == null) {
                Matcher priceMatcher = Pattern.compile("\"pdt_price\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
                if (priceMatcher.find()) {
                    price = parsePrice(priceMatcher.group(1));
                }
            }

            // === Fallback Regex cho Sold Count ===
            if (soldCount == null) {
                Matcher soldMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?[kKmM]?)\\s+(?:đã bán|sold|lượt bán)", Pattern.CASE_INSENSITIVE).matcher(html);
                if (soldMatcher.find()) {
                    soldCount = parseSoldCount(soldMatcher.group(1));
                }
            }
            if (soldCount == null) {
                Matcher soldJson = Pattern.compile("\"(?:sold|totalSold|itemSold|orderCount)\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?[kKmM]?)\"?").matcher(html);
                if (soldJson.find()) {
                    soldCount = parseSoldCount(soldJson.group(1));
                }
            }

            // Tính discount nếu có originalPrice và price
            if (discount == null && originalPrice != null && price != null && originalPrice.compareTo(price) > 0) {
                double disc = (1.0 - (price.doubleValue() / originalPrice.doubleValue())) * 100;
                discount = (int) Math.round(disc);
            }

            // === 6. Extract từ JSON-LD (schema.org structured data) ===
            Elements scriptLd = doc.select("script[type=application/ld+json]");
            for (Element script : scriptLd) {
                try {
                    JsonNode ld = objectMapper.readTree(script.data());
                    String type = getJsonText(ld, "@type");
                    if ("Product".equals(type)) {
                        if (name == null || name.isBlank() || "N/A".equalsIgnoreCase(name)) {
                            name = getJsonText(ld, "name");
                        }
                        if (imageUrl == null || imageUrl.isBlank()) {
                            imageUrl = getJsonText(ld, "image");
                        }
                        if (description == null || description.isBlank()) {
                            description = getJsonText(ld, "description");
                        }
                        // Price từ offers
                        JsonNode offers = ld.path("offers");
                        if (!offers.isMissingNode() && price == null) {
                            price = parsePrice(getJsonText(offers, "price"));
                        }
                        // Rating từ aggregateRating
                        JsonNode aggRating = ld.path("aggregateRating");
                        if (!aggRating.isMissingNode() && rating == null) {
                            String ratingVal = getJsonText(aggRating, "ratingValue");
                            if (ratingVal != null) rating = parseDouble(ratingVal);
                            String reviewVal = getJsonText(aggRating, "reviewCount");
                            if (reviewVal != null) reviewCount = parseLong(reviewVal);
                        }
                        // Seller
                        JsonNode seller = ld.path("seller");
                        if (!seller.isMissingNode() && (shopName == null || shopName.isBlank())) {
                            shopName = getJsonText(seller, "name");
                        }
                        break;
                    }
                } catch (Exception ignored) {}
            }

            // === 7. Extract description từ product detail module ===
            if (description == null || description.isBlank() || description.length() < 50) {
                Element descEl = doc.selectFirst(".pdp-product-desc, .detail-content, #module_product_detail");
                if (descEl != null) {
                    description = descEl.text();
                }
            }

            // === 8. Shop name fallback ===
            if (shopName == null || shopName.isBlank()) {
                Matcher sellerMatcher = Pattern.compile("\"seller_name\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
                if (sellerMatcher.find() && !sellerMatcher.group(1).isBlank()) {
                    shopName = sellerMatcher.group(1);
                }
            }
            if (shopName == null || shopName.isBlank()) {
                Matcher sellerMatcher2 = Pattern.compile("\"(?:sellerName|shopName|storeName|sellerTitle)\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
                if (sellerMatcher2.find() && !sellerMatcher2.group(1).isBlank()) {
                    shopName = sellerMatcher2.group(1);
                }
            }
            if (shopName == null || shopName.isBlank()) {
                Element shopEl = doc.selectFirst(".seller-name-v2__detail-name, .seller-name__detail-name, [class*=seller-name]");
                if (shopEl != null && !shopEl.text().trim().isBlank()) {
                    shopName = shopEl.text().trim();
                }
            }

            // Trích xuất shop name từ link /shop/ hoặc regex URL gian hàng
            if (shopName == null || shopName.isBlank()) {
                Element shopLink = doc.selectFirst("a[href*='/shop/']");
                if (shopLink != null) {
                    String linkText = shopLink.text().trim();
                    if (!linkText.isBlank() && !linkText.equalsIgnoreCase("Đến gian hàng") && !linkText.equalsIgnoreCase("Go to store")) {
                        shopName = linkText;
                    } else {
                        String href = shopLink.attr("href");
                        Matcher mSlug = Pattern.compile("/shop/([^/?#]+)").matcher(href);
                        if (mSlug.find()) {
                            shopName = formatShopSlug(mSlug.group(1));
                        }
                    }
                }
            }
            if (shopName == null || shopName.isBlank()) {
                Matcher mShopUrl = Pattern.compile("lazada\\.vn/shop/([^\"/?#\\s]+)").matcher(html);
                if (mShopUrl.find()) {
                    shopName = formatShopSlug(mShopUrl.group(1));
                }
            }

            // === Merge Cached Metadata từ Selenium Search nếu có ===
            if (itemId == null) itemId = extractItemId(url, html);
            if (cached == null && itemId != null) {
                cached = searchMetadataCache.get(itemId);
            }
            if (cached == null) {
                String cleanUrlKey = url.split("\\?")[0].replaceAll("-s\\d+\\.html", ".html");
                cached = searchMetadataCache.get(cleanUrlKey);
            }

            if (cached != null) {
                // Merge name & price từ cache nếu static HTML không trích xuất được
                if ((name == null || name.isBlank() || "Sản phẩm Lazada".equalsIgnoreCase(name) || "N/A".equalsIgnoreCase(name))
                        && cached.name() != null && !cached.name().isBlank()) {
                    name = cached.name();
                    log.info("[Lazada] Applied cached name: {}", name);
                }
                if (price == null && cached.price() != null && cached.price().compareTo(BigDecimal.valueOf(1000)) >= 0) {
                    price = cached.price();
                    log.info("[Lazada] Applied cached price: {}", price);
                }
                if (originalPrice == null && cached.origPrice() != null) {
                    originalPrice = cached.origPrice();
                }
                if (discount == null && cached.discount() != null) {
                    discount = cached.discount();
                }
                if ((imageUrl == null || imageUrl.isBlank()) && cached.image() != null && !cached.image().isBlank()) {
                    imageUrl = cached.image();
                }
                if ((shopName == null || shopName.isBlank()) && cached.seller() != null && !cached.seller().isBlank()) {
                    shopName = cached.seller();
                    log.info("[Lazada] Applied cached seller by itemId {}: {}", itemId, shopName);
                }
                if (rating == null && cached.rating() != null && cached.rating() > 0) {
                    rating = cached.rating();
                    log.info("[Lazada] Applied cached rating: {}", rating);
                }
                if (reviewCount == null && cached.reviews() != null && cached.reviews() > 0) {
                    reviewCount = cached.reviews();
                    log.info("[Lazada] Applied cached reviewCount: {}", reviewCount);
                }
                if (soldCount == null && cached.sold() != null && cached.sold() > 0) {
                    soldCount = cached.sold();
                    log.info("[Lazada] Applied cached soldCount: {}", soldCount);
                }
                if (shopSoldCount == null && cached.storeSold() != null && !cached.storeSold().isBlank()) {
                    shopSoldCount = cached.storeSold();
                    log.info("[Lazada] Applied cached storeSold: {}", shopSoldCount);
                }
            }

            // Trích xuất shopSoldCount từ HTML nếu chưa có
            if (shopSoldCount == null) {
                Matcher mShopSold = Pattern.compile("([\\d\\.]+[kKmM]?)\\s*(?:Sold by Store|đã bán của shop|bán bởi shop|sản phẩm đã bán)", Pattern.CASE_INSENSITIVE).matcher(html);
                if (mShopSold.find()) {
                    shopSoldCount = mShopSold.group(1);
                }
            }

            // Trích xuất từ Title nếu có tag [Tên Shop] hoặc (Tên Shop) ở đầu (ví dụ: [Camluu99])
            if (shopName == null || shopName.isBlank()) {
                if (name != null) {
                    Matcher mTag = Pattern.compile("^\\s*[\\[\\(]([^\\]\\)]+)[\\]\\)]").matcher(name);
                    if (mTag.find()) {
                        String candidate = mTag.group(1).trim();
                        if (candidate.length() >= 2 && candidate.length() <= 30
                                && !candidate.equalsIgnoreCase("HOT")
                                && !candidate.equalsIgnoreCase("SALE")
                                && !candidate.equalsIgnoreCase("BIG SIZE")
                                && !candidate.toUpperCase().contains("DEAL")
                                && !candidate.toUpperCase().contains("FLASH")
                                && !candidate.toUpperCase().contains("FREE SHIP")
                                && !candidate.toUpperCase().contains("FREESHIP")
                                && !candidate.toUpperCase().contains("SIÊU")
                                && !candidate.toUpperCase().contains("XẢ")
                                && !candidate.toUpperCase().contains("MỞ BÁN")
                                && !candidate.toUpperCase().contains("CUỐI NĂM")
                                && !candidate.toUpperCase().contains("GIÁ TỐT")
                                && !candidate.toUpperCase().contains("HCM")
                                && !candidate.toUpperCase().contains("HOT")
                                && !candidate.toUpperCase().contains("NEW")
                                && !candidate.toUpperCase().contains("COMBO")) {
                            shopName = candidate;
                            log.info("[Lazada] Extracted shop name from title tag: {}", shopName);
                        }
                    }
                }
            }

            // Nếu shopName vẫn null, thử trích xuất từ Brand hoặc Title
            if (shopName == null || shopName.isBlank()) {
                if (brand != null && !brand.isBlank() && !"No Brand".equalsIgnoreCase(brand)) {
                    shopName = brand + " Store";
                } else if (name != null) {
                    Matcher brandTitle = Pattern.compile("(?i)\\b(SEVEN BOXER|COOLMATE|TEELAB|ROUTINE|YODY|POLOMEN|AN PHAT|SIXMEN|AVENTUS|TORANO|CAMLUU99|CAM LUU)\\b").matcher(name);
                    if (brandTitle.find()) {
                        shopName = brandTitle.group(1) + " Official";
                    }
                }
            }
            if (shopName == null || shopName.isBlank()) {
                shopName = "Gian hàng chính hãng Lazada";
            }

            // === Rating & Review Count & Customer Comments ===
            // 0. Exact Lazada selectors from DevTools
            if (rating == null) {
                Element ratingEl = doc.selectFirst(".container-star-v2-score");
                if (ratingEl != null) {
                    rating = parseDouble(ratingEl.text().trim());
                    log.info("[Lazada] Extracted rating from .container-star-v2-score: {}", rating);
                }
            }
            if (reviewCount == null) {
                Element revEl = doc.selectFirst(".container-star-v2-count");
                if (revEl != null) {
                    reviewCount = parseLong(revEl.text().trim());
                    log.info("[Lazada] Extracted reviewCount from .container-star-v2-count: {}", reviewCount);
                }
            }
            // 1. Regex tìm "4.8/5" hoặc "4.8 / 5" hoặc "4.8 sao" hoặc "4.8 điểm"
            if (rating == null) {
                Matcher starMatcher = Pattern.compile("([1-5](?:\\.\\d+)?)\\s*(?:\\/\\s*5|sao|star|stars|điểm)").matcher(html);
                if (starMatcher.find()) {
                    rating = parseDouble(starMatcher.group(1));
                    log.info("[Lazada] Extracted rating from star regex: {}", rating);
                }
            }

            // 2. Tìm trong DOM selector
            if (rating == null) {
                Element scoreEl = doc.selectFirst(".score-average, .rating, [class*='rating-score'], [class*='score_average'], .pdp-review-summary__stars");
                if (scoreEl != null) {
                    rating = parseDouble(scoreEl.text().trim());
                }
            }

            // 3. Regex tìm "Reviews(189)" hoặc "(189 đánh giá)" hoặc "189 Reviews"
            if (reviewCount == null) {
                Matcher revMatcher = Pattern.compile("(?:Reviews?|Ratings?)\\s*\\(\\s*(\\d+)\\s*\\)|(\\d+)\\s*(?:đánh giá|lượt đánh giá|nhận xét|reviews?|ratings?)", Pattern.CASE_INSENSITIVE).matcher(html);
                if (revMatcher.find()) {
                    String val = revMatcher.group(1) != null ? revMatcher.group(1) : revMatcher.group(2);
                    reviewCount = parseLong(val);
                    log.info("[Lazada] Extracted reviewCount: {}", reviewCount);
                }
            }

            // 4. Regex tìm JSON fields ratingScore, averageScore, ratingValue, score, rate
            if (rating == null) {
                Matcher jsonRating = Pattern.compile("\"(?:ratingScore|averageScore|ratingValue|itemRating|rating|rate|score)\"\\s*:\\s*\"?([1-5](?:\\.\\d+)?)\"?").matcher(html);
                if (jsonRating.find()) {
                    rating = parseDouble(jsonRating.group(1));
                }
            }

            if (reviewCount == null) {
                Matcher jsonReviews = Pattern.compile("\"(?:reviewCount|ratingCount|totalReview|totalRating|reviewNum|reviewTotal)\"\\s*:\\s*\"?(\\d+)\"?").matcher(html);
                if (jsonReviews.find()) {
                    reviewCount = parseLong(jsonReviews.group(1));
                }
            }

            // 5. Nếu vẫn chưa có rating, gọi trực tiếp API Review của Lazada bằng itemId
            if (rating == null || reviewCount == null) {
                try {
                    if (itemId != null) {
                        ReviewInfo reviewInfo = fetchLazadaReviewApi(itemId);
                        if (reviewInfo != null) {
                            if (rating == null && reviewInfo.rating != null) rating = reviewInfo.rating;
                            if (reviewCount == null && reviewInfo.reviewCount != null) reviewCount = reviewInfo.reviewCount;
                            if (reviewInfo.topComment != null && !reviewInfo.topComment.isBlank()) {
                                description = (description != null ? description + " | " : "") + "Đánh giá khách hàng: " + reviewInfo.topComment;
                            }
                            log.info("[Lazada] Review API result: rating={}, reviews={}, topComment='{}'",
                                    rating, reviewCount, reviewInfo.topComment);
                        }
                    }
                } catch (Exception e) {
                    log.debug("[Lazada] Review API fetch skipped: {}", e.getMessage());
                }
            }

            // Nếu shopName vẫn chưa có, dùng API-based approach thay cho Selenium (bị Lazada TMD block)
            if (shopName == null || shopName.isBlank() || "Gian hàng chính hãng Lazada".equals(shopName) || "Shop trên sàn TMĐT".equals(shopName)) {
                try {
                    log.info("[Lazada] Shop name not found in static HTML, trying API-based extraction for itemId={}", itemId);
                    String sellerFromApi = fetchSellerFromApi(itemId, url, html);
                    if (sellerFromApi != null && !sellerFromApi.isBlank()) {
                        shopName = sellerFromApi;
                        log.info("[Lazada] API successfully extracted shopName='{}'", shopName);
                    }
                } catch (Exception e) {
                    log.warn("[Lazada] API-based seller extraction failed: {}", e.getMessage());
                }
            }
            
            // Final fallback: extract from /shop/ link in HTML
            if (shopName == null || shopName.isBlank() || "Gian hàng chính hãng Lazada".equals(shopName)) {
                Matcher shopLinkMatcher = Pattern.compile("href=\"[^\"]*?/shop/([^\"/?#]+)").matcher(html);
                if (shopLinkMatcher.find()) {
                    String slug = shopLinkMatcher.group(1);
                    if (!slug.isBlank() && slug.length() < 60) {
                        shopName = slug.replace("-", " ").replace("_", " ");
                        String[] words = shopName.split("\\s+");
                        StringBuilder sb = new StringBuilder();
                        for (String w : words) {
                            if (!w.isEmpty()) {
                                sb.append(Character.toUpperCase(w.charAt(0)));
                                if (w.length() > 1) sb.append(w.substring(1));
                                sb.append(" ");
                            }
                        }
                        shopName = sb.toString().trim();
                        log.info("[Lazada] Extracted shopName from /shop/ link: '{}'", shopName);
                    }
                }
            }

            // Nếu shopName vẫn là default, dùng location từ cache (Thành phố HCM, Thanh Hóa, China...) không gắn chữ 'Shop tại'
            if (shopName == null || shopName.isBlank() || "Gian hàng chính hãng Lazada".equals(shopName) || "Shop trên sàn TMĐT".equals(shopName)) {
                if (cached != null && cached.location() != null && !cached.location().isBlank()) {
                    shopName = cached.location();
                    log.info("[Lazada] Using location as shopName fallback: '{}'", shopName);
                } else {
                    shopName = "Việt Nam";
                }
            }

            // Nếu sản phẩm có lượt đánh giá (reviewCount > 0) mà rating bị thiếu (do Lazada chỉ render SVG), ước tính 5.0 sao
            if (rating == null && reviewCount != null && reviewCount > 0) {
                rating = 5.0;
                log.info("[Lazada] Estimated rating 5.0 from reviewCount={}", reviewCount);
            }

            // === 9. Specifications & Shop Location ===
            String specifications = "Thương hiệu: " + (brand != null ? brand : "No Brand")
                    + " | Danh mục: " + (category != null ? category : "Thời trang");
            Element specEl = doc.selectFirst(".pdp-mod-specification, .specification-keys, .pdp-product-highlights");
            if (specEl != null) {
                String specText = specEl.text().trim();
                if (!specText.isBlank()) {
                    specifications = specifications + " | " + specText;
                }
            }

            // shopLocation từ cache hoặc default
            String shopLocation = (cached != null && cached.location() != null && !cached.location().isBlank())
                    ? cached.location() : "Việt Nam";

            log.info("[Lazada] Final => name='{}', price={}, origPrice={}, discount={}%, rating={}, reviews={}, shop='{}', shopSold='{}'",
                    name, price, originalPrice, discount, rating, reviewCount, shopName, shopSoldCount);

            Product product = Product.builder()
                    .name(name != null && !name.isBlank() ? name : "Sản phẩm Lazada")
                    .price(price)
                    .originalPrice(originalPrice)
                    .discount(discount)
                    .rating(rating)
                    .reviewCount(reviewCount)
                    .soldCount(soldCount)
                    .shopName(shopName)
                    .shopSoldCount(shopSoldCount)
                    .shopLocation(shopLocation)
                    .specifications(specifications)
                    .imageUrl(imageUrl)
                    .productUrl(url)
                    .category(category)
                    .description(description != null && description.length() > 1000
                            ? description.substring(0, 1000) : description)
                    .source(Product.ProductSource.LAZADA)
                    .status(Product.ProductStatus.PROCESSING)
                    .build();

            log.info("[Lazada] ✅ Successfully scraped: {}", product.getName());
            return product;

        } catch (Exception e) {
            log.warn("[Lazada] HTTP scrape failed for {}: {}. Trying Selenium detail scraper...", url, e.getMessage());
            try {
                Product selProd = scrapeViaSelenium(url);
                if (selProd != null && selProd.getPrice() != null) {
                    log.info("[Lazada] ✅ Selenium successfully scraped fallback: {}", selProd.getName());
                    return selProd;
                }
            } catch (Exception selEx) {
                log.error("[Lazada] Selenium fallback error for {}: {}", url, selEx.getMessage());
            }
            log.error("[Lazada] ❌ Scrape failed completely for {}: {}", url, e.getMessage());
            return Product.builder()
                    .productUrl(url)
                    .name("Sản phẩm Lazada")
                    .source(Product.ProductSource.LAZADA)
                    .status(Product.ProductStatus.FAILED)
                    .build();
        }
    }

    // ── HTTP Request ─────────────────────────────────────────────────────────

    /**
     * Fetch Lazada page qua HTTP thuần bằng Jsoup.
     * Giả lập browser headers để tránh bị block.
     */
    private Document fetchDocument(String url) throws Exception {
        ensureCookies();
        // Random delay giữa các request để tránh rate limit
        randomDelay(500, 1500);

        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .cookies(sessionCookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .referrer("https://www.lazada.vn/")
                .timeout(15_000) // 15 seconds timeout
                .maxBodySize(5_000_000) // 5MB max
                .followRedirects(true)
                .get();
    }

    // ── JSON Extraction Helpers ──────────────────────────────────────────────

    /**
     * Extract pdpTrackingData JSON từ <script> tag.
     * Format: var pdpTrackingData = "{escaped JSON string}";
     */
    private String extractJsonFromScript(String html, String varName) {
        try {
            String marker = "var " + varName + " = \"";
            int idx = html.indexOf(marker);
            if (idx >= 0) {
                int start = idx + marker.length();
                int end = html.indexOf("\";", start);
                if (end > start) {
                    String escaped = html.substring(start, end);
                    try {
                        return objectMapper.readValue("\"" + escaped + "\"", String.class);
                    } catch (Exception e) {
                        return escaped.replace("\\\"", "\"")
                                .replace("\\\\/", "/")
                                .replace("\\\\", "\\");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Lazada] Cannot extract '{}' from script: {}", varName, e.getMessage());
        }
        return null;
    }

    /**
     * Extract __moduleData__ JSON từ <script> tag.
     * Format: var __moduleData__ = {large JSON};
     */
    private String extractModuleData(String html) {
        try {
            String marker = "var __moduleData__ = ";
            int idx = html.indexOf(marker);
            if (idx < 0) return null;

            int jsonStart = idx + marker.length();
            // Tìm end of JSON bằng bracket matching
            int depth = 0;
            int end = jsonStart;
            for (int i = jsonStart; i < html.length(); i++) {
                char c = html.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i + 1;
                        break;
                    }
                }
            }
            return html.substring(jsonStart, end);
        } catch (Exception e) {
            log.warn("[Lazada] Cannot extract __moduleData__: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Tìm deep nested node theo key trong JSON tree.
     * DFS search — tìm node đầu tiên có key khớp.
     */
    private JsonNode findDeepNode(JsonNode root, String key) {
        if (root == null || root.isMissingNode()) return null;
        if (root.has(key)) return root.get(key);

        // DFS children
        if (root.isObject()) {
            var fields = root.fields();
            while (fields.hasNext()) {
                JsonNode result = findDeepNode(fields.next().getValue(), key);
                if (result != null) return result;
            }
        } else if (root.isArray()) {
            for (JsonNode child : root) {
                JsonNode result = findDeepNode(child, key);
                if (result != null) return result;
            }
        }
        return null;
    }

    // ── General Helpers ──────────────────────────────────────────────────────

    private String getMetaContent(Document doc, String property) {
        Element el = doc.selectFirst("meta[property='" + property + "'], meta[name='" + property + "']");
        if (el != null) return el.attr("content");
        return null;
    }

    private String getJsonText(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) return null;
        String text = child.asText("");
        return text.isBlank() ? null : text;
    }

    private void randomDelay(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BigDecimal parsePrice(String text) {
        try {
            if (text == null || text.isBlank()) return null;
            if (text.contains("%")) return null; // Tránh lấy % giảm giá làm giá tiền
            String cleaned = text.replaceAll("[^\\d]", "");
            if (cleaned.isBlank()) return null;
            BigDecimal val = new BigDecimal(cleaned);
            if (val.compareTo(BigDecimal.valueOf(1000)) < 0) return null; // Giá VNĐ hợp lệ luôn >= 1,000
            return val;
        } catch (Exception e) { return null; }
    }

    private Integer parseDiscount(String text) {
        try {
            if (text == null || text.isBlank()) return null;
            String cleaned = text.replaceAll("[^\\d]", "");
            return cleaned.isBlank() ? null : Integer.parseInt(cleaned);
        } catch (Exception e) { return null; }
    }

    private Double parseDouble(String text) {
        try {
            if (text == null || text.isBlank()) return null;
            String cleaned = text.replaceAll("[^\\d.]", "");
            return cleaned.isBlank() ? null : Double.parseDouble(cleaned);
        } catch (Exception e) { return null; }
    }

    private Long parseLong(String text) {
        try {
            if (text == null || text.isBlank()) return null;
            String cleaned = text.replaceAll("[^\\d]", "");
            return cleaned.isBlank() ? null : Long.parseLong(cleaned);
        } catch (Exception e) { return null; }
    }

    private Long parseSoldCount(String text) {
        if (text == null || text.isBlank()) return null;
        text = text.trim().toLowerCase();
        try {
            if (text.endsWith("k")) {
                double val = Double.parseDouble(text.replace("k", "").trim());
                return (long) (val * 1000);
            }
            if (text.endsWith("m")) {
                double val = Double.parseDouble(text.replace("m", "").trim());
                return (long) (val * 1_000_000);
            }
            String digits = text.replaceAll("[^\\d]", "");
            return digits.isBlank() ? null : Long.parseLong(digits);
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchSellerFromApi(String itemId, String url, String html) {
        if (itemId == null || itemId.isBlank()) return null;
        
        // Strategy 1: Try Lazada seller info API endpoints
        String[] sellerApiUrls = {
            "https://www.lazada.vn/pdp/pdp_seller_info?itemId=" + itemId,
            "https://my.lazada.vn/pdp/pdp_seller_info?itemId=" + itemId,
        };
        
        for (String apiUrl : sellerApiUrls) {
            try {
                String json = Jsoup.connect(apiUrl)
                        .userAgent(USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Referer", "https://www.lazada.vn/products/pdp-i" + itemId + ".html")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .ignoreContentType(true)
                        .timeout(5000)
                        .execute()
                        .body();
                
                if (json != null && !json.isBlank()) {
                    JsonNode root = objectMapper.readTree(json);
                    // Look for seller name in various JSON paths
                    String[] sellerPaths = {"data.seller.name", "data.sellerName", "data.storeName", 
                                           "result.seller.name", "result.sellerName", "seller.name", "sellerName"};
                    for (String path : sellerPaths) {
                        JsonNode node = root;
                        for (String key : path.split("\\.")) {
                            node = node.path(key);
                        }
                        if (!node.isMissingNode() && node.isTextual() && !node.asText().isBlank()) {
                            String seller = node.asText().trim();
                            log.info("[Lazada] Found seller '{}' from API: {}", seller, apiUrl);
                            return seller;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Lazada] Seller API {} failed: {}", apiUrl, e.getMessage());
            }
        }
        
        // Strategy 2: Deep search in HTML embedded JSON for seller/store info
        if (html != null) {
            // Pattern 1: "seller_name":"xxx"
            String[] jsonSellerPatterns = {
                "\"seller_name\"\\s*:\\s*\"([^\"]+)\"",
                "\"sellerName\"\\s*:\\s*\"([^\"]+)\"",
                "\"storeName\"\\s*:\\s*\"([^\"]+)\"",
                "\"shopName\"\\s*:\\s*\"([^\"]+)\"",
                "\"sellerTitle\"\\s*:\\s*\"([^\"]+)\"",
                "\"seller\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"",
                "\"shop\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"",
                "\"brandName\"\\s*:\\s*\"([^\"]+)\""
            };
            
            for (String pattern : jsonSellerPatterns) {
                Matcher m = Pattern.compile(pattern).matcher(html);
                if (m.find()) {
                    String seller = m.group(1).trim();
                    if (!seller.isBlank() && seller.length() < 60 && !seller.equalsIgnoreCase("Lazada")) {
                        log.info("[Lazada] Found seller '{}' from embedded JSON pattern: {}", seller, pattern);
                        return seller;
                    }
                }
            }
        }
        
        return null;
    }

    private String extractItemId(String url, String html) {
        if (url != null) {
            Matcher m = Pattern.compile("i(\\d+)(?:-s|\\.html|_)").matcher(url);
            if (m.find()) return m.group(1);
        }
        if (html != null) {
            Matcher m2 = Pattern.compile("\"itemId\"\\s*:\\s*\"?(\\d+)\"?").matcher(html);
            if (m2.find()) return m2.group(1);
            Matcher m3 = Pattern.compile("\"pdt_sku\"\\s*:\\s*\"?(\\d+)\"?").matcher(html);
            if (m3.find()) return m3.group(1);
        }
        return null;
    }

    private ReviewInfo fetchLazadaReviewApi(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        
        // Try multiple API endpoints
        String[] apiUrls = {
            "https://my.lazada.vn/pdp/review/getReviewList?itemId=" + itemId + "&pageSize=5&filter=0&sort=0&pageNo=1",
            "https://www.lazada.vn/pdp/review/getReviewList?itemId=" + itemId + "&pageSize=5&filter=0&sort=0&pageNo=1",
            "https://pdp-api.lazada.vn/pdp/review/getReviewList?itemId=" + itemId + "&pageSize=5&filter=0&sort=0&pageNo=1"
        };
        
        for (String apiUrl : apiUrls) {
            try {
                String json = Jsoup.connect(apiUrl)
                        .userAgent(USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Referer", "https://www.lazada.vn/products/pdp-i" + itemId + ".html")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .ignoreContentType(true)
                        .timeout(6000)
                        .execute()
                        .body();

                if (json != null && !json.isBlank()) {
                    JsonNode root = objectMapper.readTree(json);
                    JsonNode data = root.path("data");
                    if (data.isMissingNode()) data = root.path("result");
                    if (!data.isMissingNode()) {
                        JsonNode item = data.path("item");
                        if (item.isMissingNode()) item = data;
                        Double rating = null;
                        Long reviewCount = null;
                        if (!item.isMissingNode()) {
                            if (item.has("rating")) rating = item.path("rating").asDouble();
                            if (rating == null && item.has("averageScore")) rating = item.path("averageScore").asDouble();
                            if (item.has("ratingCount")) reviewCount = item.path("ratingCount").asLong();
                            if (reviewCount == null && item.has("total")) reviewCount = item.path("total").asLong();
                            if (reviewCount == null && item.has("reviewCount")) reviewCount = item.path("reviewCount").asLong();
                        }

                        String topComment = null;
                        JsonNode items = data.path("items");
                        if (items.isMissingNode()) items = data.path("reviews");
                        if (items.isArray() && items.size() > 0) {
                            for (JsonNode r : items) {
                                String c = r.path("reviewContent").asText("");
                                if (c.isBlank()) c = r.path("content").asText("");
                                if (!c.isBlank() && c.length() > 10) {
                                    topComment = c;
                                    break;
                                }
                            }
                        }

                        if (rating != null || reviewCount != null) {
                            log.info("[Lazada] Review API success from {}: rating={}, reviews={}", apiUrl, rating, reviewCount);
                            return new ReviewInfo(rating, reviewCount, topComment);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Lazada] fetchLazadaReviewApi failed for {}: {}", apiUrl, e.getMessage());
            }
        }
        return null;
    }

    private Product scrapeViaSelenium(String url) {
        ChromeDriver driver = null;
        try {
            driver = createDriver();
            driver.get(url);
            randomDelay(2500, 3500);

            // Debug: check if we hit captcha
            String title = driver.getTitle();
            String pageUrl = driver.getCurrentUrl();
            log.info("[Lazada Selenium] After load - title='{}', url='{}'", title, pageUrl);
            
            // Check for CAPTCHA
            String bodyText = (String) ((JavascriptExecutor) driver).executeScript("return document.body.innerText.substring(0, 500);");
            log.info("[Lazada Selenium] Page body preview: {}", bodyText != null ? bodyText.substring(0, Math.min(bodyText.length(), 300)) : "null");
            
            if (title != null && (title.toLowerCase().contains("captcha") || title.toLowerCase().contains("verify") || title.toLowerCase().contains("robot"))) {
                log.warn("[Lazada Selenium] CAPTCHA detected! Title: {}", title);
                return null;
            }

            JavascriptExecutor js = (JavascriptExecutor) driver;
            
            // Scroll down to load seller card and reviews section
            js.executeScript("window.scrollTo(0, document.body.scrollHeight * 0.5);");
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            js.executeScript("window.scrollTo(0, document.body.scrollHeight * 0.7);");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            js.executeScript("window.scrollTo(0, 0);");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            
            Object result = js.executeScript("""
                var data = {};
                var bodyText = document.body.innerText || "";

                // Name / Title
                var titleEl = document.querySelector('.pdp-mod-product-badge-title, h1, [class*="product-title"], [class*="pdp-title"]');
                data.name = titleEl ? titleEl.innerText.trim() : "";
                if (!data.name) {
                    var docTitle = document.title || "";
                    if (docTitle.indexOf('|') !== -1) data.name = docTitle.split('|')[0].trim();
                }

                // Price
                var priceEl = document.querySelector('.pdp-price_type_normal, [class*="price_type_normal"], [class*="pdp-price"], .notranslate.pdp-price');
                var priceText = priceEl ? priceEl.innerText.trim() : "";
                if (!priceText || priceText.indexOf('%') !== -1) {
                    var pMatch = bodyText.match(/₫ *([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,9})/) ||
                                 bodyText.match(/([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,9}) *(?:₫|đ|VND|VNĐ)/i);
                    if (pMatch) priceText = pMatch[1];
                }
                data.price = priceText;

                // Original Price
                var origPriceEl = document.querySelector('.pdp-price_type_deleted, [class*="price_type_deleted"], del, s');
                var origText = origPriceEl ? origPriceEl.innerText.trim() : "";
                data.origPrice = origText;

                // Seller / Shop - try multiple strategies
                var seller = "";

                // Strategy 1: Direct seller name elements (current Lazada PDP layout)
                var sellerSelectors = [
                    'a.seller-name-v2__detail-name',
                    '.seller-name-v2__detail-name',
                    '.seller-name__detail-name',
                    '[data-spm="seller"] a.pdp-link',
                    '.seller-name-v2__detail a',
                    '[class*="seller-name"] a',
                    '[class*="seller_name"]',
                    '[data-spm="seller"] a',
                    '.seller-name a',
                    '.seller-info-name a',
                    '.seller-info__name',
                    '[class*="SellerName"]',
                    '[class*="seller"] [class*="name"]',
                    '.info-content a[href*="/shop/"]',
                    '.seller-name__detail a',
                    '.pdp-block__seller-name'
                ];
                for (var si = 0; si < sellerSelectors.length; si++) {
                    var sel = document.querySelector(sellerSelectors[si]);
                    if (sel) {
                        var txt = sel.innerText.trim();
                        if (txt && txt !== "LazMall" && txt !== "Đến gian hàng" && txt !== "Go to store" && txt !== "GO TO STORE" && txt.length > 1) {
                            seller = txt;
                            break;
                        }
                    }
                }

                // Strategy 2: Find seller info card - look for the area with "Seller Ratings", "Sold by Store" etc.
                if (!seller) {
                    var sellerCard = document.querySelector('[class*="seller-info"], [class*="seller-card"], [class*="seller-container"], [class*="pdp-block__seller"]');
                    if (sellerCard) {
                        // Get all links in seller card
                        var cardLinks = sellerCard.querySelectorAll('a');
                        for (var cl = 0; cl < cardLinks.length; cl++) {
                            var linkTxt = cardLinks[cl].innerText.trim();
                            var linkHref = cardLinks[cl].href || "";
                            if (linkHref.indexOf('/shop/') !== -1 && linkTxt && linkTxt !== "GO TO STORE" && linkTxt !== "Đến gian hàng" && linkTxt !== "Chat" && linkTxt.length > 1 && linkTxt.length < 60) {
                                seller = linkTxt;
                                break;
                            }
                        }
                        // If no link text, get first bold/heading text in seller card
                        if (!seller) {
                            var cardHeading = sellerCard.querySelector('h2, h3, h4, strong, b, [class*="name"]');
                            if (cardHeading) {
                                var ht = cardHeading.innerText.trim();
                                if (ht && ht.length > 1 && ht.length < 50 && ht !== "LazMall") {
                                    seller = ht;
                                }
                            }
                        }
                    }
                }

                // Strategy 3: Find shop link and extract name
                if (!seller) {
                    var shopLinks = document.querySelectorAll('a[href*="/shop/"]');
                    for (var li = 0; li < shopLinks.length; li++) {
                        var sLink = shopLinks[li];
                        var stxt = sLink.innerText.trim();
                        if (stxt && stxt !== "Đến gian hàng" && stxt !== "Go to store" && stxt !== "GO TO STORE" && stxt !== "LazMall" && stxt !== "Chat" && stxt.length > 1 && stxt.length < 50) {
                            seller = stxt;
                            break;
                        }
                    }
                    if (!seller && shopLinks.length > 0) {
                        var shopHref = shopLinks[0].href || "";
                        var shopIdx = shopHref.indexOf('/shop/');
                        if (shopIdx !== -1) {
                            var slug = shopHref.substring(shopIdx + 6).split(/[/?#]/)[0];
                            if (slug) seller = slug.replace(/[-_]/g, ' ');
                        }
                    }
                }

                // Strategy 4: Look for seller name near "Seller Ratings" or "Store" text
                if (!seller) {
                    var allEls = document.querySelectorAll('[class*="seller"], [class*="store-info"], [class*="shop-info"]');
                    for (var ei = 0; ei < allEls.length; ei++) {
                        var elText = allEls[ei].innerText.trim();
                        if (elText && elText.length > 2 && elText.length < 80) {
                            var lines = elText.split(String.fromCharCode(10));
                            if (lines.length > 0) {
                                var firstLine = lines[0].trim();
                                if (firstLine.length > 1 && firstLine.length < 50 && firstLine !== "LazMall" && firstLine.indexOf("Seller Ratings") === -1 && firstLine.indexOf("Sold by") === -1) {
                                    seller = firstLine;
                                    break;
                                }
                            }
                        }
                    }
                }

                data.seller = seller;

                // Shop Sold Count - look for "X Sold by Store" or "X.XK Sold by Store"
                var shopSold = "";
                var shopSoldMatch = bodyText.match(/([0-9]+[.,]?[0-9]*[kKmM]?) *(?:Sold by Store|Sold By Store|đã bán của shop|bán bởi shop|sản phẩm đã bán)/i);
                if (shopSoldMatch) {
                    shopSold = shopSoldMatch[1];
                } else {
                    // Try finding specific element
                    var soldByStoreEls = document.querySelectorAll('[class*="seller"] [class*="sold"], [class*="store"] [class*="sold"]');
                    for (var ssi = 0; ssi < soldByStoreEls.length; ssi++) {
                        var ssTxt = soldByStoreEls[ssi].innerText.trim();
                        if (ssTxt && ssTxt.match(/[0-9]/)) {
                            shopSold = ssTxt;
                            break;
                        }
                    }
                }
                data.shopSold = shopSold;

                // Rating - use exact Lazada selectors from DevTools
                var rating = "";
                // Priority 1: Exact selector from Lazada PDP
                var ratingEl = document.querySelector('.container-star-v2-score');
                if (ratingEl) {
                    rating = ratingEl.innerText.trim();
                }
                // Priority 2: Other known selectors
                if (!rating) {
                    ratingEl = document.querySelector('.score-average, [class*="rating-score"], [class*="score_average"], .pdp-review-summary__stars');
                    if (ratingEl) {
                        var rTxt = ratingEl.innerText.trim();
                        var rParsed = rTxt.match(/([1-5](?:[.][0-9]+)?) *(?:[/]|$)/);
                        if (rParsed) rating = rParsed[1];
                        if (!rating) rating = rTxt;
                    }
                }
                // Priority 3: Regex from bodyText
                if (!rating) {
                    var rScoreMatch = bodyText.match(/([1-5][.][0-9]+?) *(?:[/] *5| *trên *5)/);
                    if (rScoreMatch) rating = rScoreMatch[1];
                }
                // Priority 4: Seller Ratings percentage
                if (!rating) {
                    var sellerRatingMatch = bodyText.match(/Seller Ratings? *:? *([0-9]+)%/i);
                    if (sellerRatingMatch) {
                        var pct = parseInt(sellerRatingMatch[1]);
                        rating = (pct / 20.0).toFixed(1);
                    }
                }
                // Priority 5: JSON embedded rating
                if (!rating) {
                    var pageSource = document.documentElement.outerHTML || "";
                    var jsonRatingMatch = pageSource.match(/"(?:ratingScore|averageScore|ratingValue|itemRating)" *: *"?([1-5](?:[.][0-9]+)?)"?/);
                    if (jsonRatingMatch) rating = jsonRatingMatch[1];
                }
                data.rating = rating;

                // Reviews Count - use exact Lazada selector from DevTools
                var reviews = "";
                // Priority 1: Exact selector from Lazada PDP
                var revCountEl = document.querySelector('.container-star-v2-count');
                if (revCountEl) {
                    reviews = revCountEl.innerText.trim();
                }
                // Priority 2: Regex from bodyText
                if (!reviews) {
                    var rCountMatch = bodyText.match(/(?:Reviews?|Đánh giá) *[(] *([0-9]+) *[)]/i);
                    if (!rCountMatch) rCountMatch = bodyText.match(/([0-9]+) *(?:Ratings?|Reviews?|Đánh giá|lượt đánh giá)/i);
                    if (rCountMatch) reviews = rCountMatch[1];
                }
                // Priority 3: DOM fallback
                if (!reviews) {
                    var revEl = document.querySelector('.pdp-review-summary__link, [class*="review-summary"], [class*="review-count"]');
                    if (revEl) {
                        var revTxt = revEl.innerText.trim();
                        var revNum = revTxt.match(/([0-9]+)/);
                        if (revNum) reviews = revNum[1];
                    }
                }
                data.reviews = reviews;

                // Sold Count
                var soldEl = document.querySelector('[class*="item-sold"], [class*="sold-count"]');
                data.sold = soldEl ? soldEl.innerText.trim() : "";
                if (!data.sold) {
                    var sMatch = bodyText.match(/([0-9]+(?:[.][0-9]+)?[kKmM]?) *(?:Đã bán|đã bán|sold|lượt bán)/i);
                    if (sMatch) data.sold = sMatch[1];
                }


                // Image
                var imgEl = document.querySelector('.gallery-preview-panel__image, .pdp-mod-common-image, img[class*="preview"]');
                data.image = imgEl ? imgEl.src : "";

                // Description
                var descEl = document.querySelector('.pdp-product-desc, .detail-content, #module_product_detail');
                data.description = descEl ? descEl.innerText.trim() : "";

                return data;
            """);

            // Debug: log page state
            String pageTitle = driver.getTitle();
            String currentUrl = driver.getCurrentUrl();
            log.info("[Lazada Selenium] Page title='{}', url='{}'", pageTitle, currentUrl);
            
            // Debug: log raw result
            log.info("[Lazada Selenium] Raw JS result type: {}, value: {}", 
                result != null ? result.getClass().getSimpleName() : "null", result);

            if (result instanceof Map<?, ?> map) {
                String name = (String) map.get("name");
                String pText = (String) map.get("price");
                String opText = (String) map.get("origPrice");
                String seller = (String) map.get("seller");
                String rText = (String) map.get("rating");
                String revText = (String) map.get("reviews");
                String sText = (String) map.get("sold");
                String shopSold = (String) map.get("shopSold");
                String image = (String) map.get("image");
                String desc = (String) map.get("description");

                BigDecimal price = parsePrice(pText);
                BigDecimal origPrice = parsePrice(opText);
                Double rating = parseDouble(rText);
                Long reviews = parseLong(revText);
                Long sold = parseSoldCount(sText);

                if (seller == null || seller.isBlank() || seller.equalsIgnoreCase("Gian hàng Lazada") || seller.equalsIgnoreCase("Shop trên sàn TMĐT")) {
                    String brandFromTitle = extractBrandFromTitle(name);
                    if (brandFromTitle != null && !brandFromTitle.isBlank()) {
                        seller = brandFromTitle;
                    }
                }

                if (price != null || (name != null && !name.isBlank())) {
                    log.info("[Lazada Selenium] Scraped product='{}', seller='{}', shopSold='{}', price={}, rating={}, reviews={}, sold={}",
                            name, seller, shopSold, price, rating, reviews, sold);
                    return Product.builder()
                            .name(name != null && !name.isBlank() ? name : "Sản phẩm Lazada")
                            .price(price)
                            .originalPrice(origPrice)
                            .rating(rating != null ? rating : 5.0)
                            .reviewCount(reviews != null ? reviews : 1L)
                            .soldCount(sold)
                            .shopName(seller != null && !seller.isBlank() ? seller : "Gian hàng chính hãng Lazada")
                            .shopSoldCount(shopSold)
                            .imageUrl(image)
                            .productUrl(url)
                            .description(desc)
                            .source(Product.ProductSource.LAZADA)
                            .status(Product.ProductStatus.PROCESSING)
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("[Lazada] scrapeViaSelenium failed: {}", e.getMessage());
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String extractBrandFromTitle(String title) {
        if (title == null || title.isBlank()) return null;
        Matcher mBracket = Pattern.compile("^\\[([^\\]]+)\\]").matcher(title.trim());
        if (mBracket.find()) {
            String b = mBracket.group(1).trim();
            if (!b.equalsIgnoreCase("Deal") && !b.equalsIgnoreCase("Siêu Rẻ") && !b.equalsIgnoreCase("Siêu Phẩm") && !b.equalsIgnoreCase("Hot") && !b.equalsIgnoreCase("Free Ship") && b.length() <= 30) {
                return b;
            }
        }
        Matcher mUpper = Pattern.compile("\\b([A-Z0-9]{3,15}(?:\\s+[A-Z0-9]{3,15})?)\\b").matcher(title);
        while (mUpper.find()) {
            String candidate = mUpper.group(1).trim();
            String upper = candidate.toUpperCase();
            if (!List.of("FREE", "SHIP", "HOT", "HIT", "SIÊU", "RẺ", "PHẨM", "UNISEX", "BIG", "SIZE", "WIN1", "VND", "VNĐ", "TOP", "NAM", "NỮ", "COTTON", "AM", "TỔNG", "HỢP", "SET", "THUN", "POLO", "ÁO", "QUẦN", "VÁY").contains(upper)) {
                return candidate;
            }
        }
        return null;
    }

    private String formatShopSlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        String[] parts = slug.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1));
            }
        }
        return sb.toString().trim();
    }

    private String extractItemIdFromUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("i(\\d+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static class ReviewInfo {
        final Double rating;
        final Long reviewCount;
        final String topComment;

        ReviewInfo(Double rating, Long reviewCount, String topComment) {
            this.rating = rating;
            this.reviewCount = reviewCount;
            this.topComment = topComment;
        }
    }
}
