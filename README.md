# E-Commerce Scraper & AI Analysis Platform - Backend

---

## TABLE OF CONTENTS / MỤC LỤC

1. [BẢN TIẾNG VIỆT (VIETNAMESE VERSION)](#i-bản-tiếng-việt)
   - [1. Giới thiệu bài toán & Mục tiêu](#1-giới-thiệu-bài-toán--mục-tiêu)
   - [2. Kiến trúc hệ thống & Backend Stack](#2-kiến-trúc-hệ-thống--backend-stack)
   - [3. Cơ chế vượt rào bảo vệ & Anti-Bot Scraping](#3-cơ-chế-vượt-rào-bảo-vệ--anti-bot-scraping)
   - [4. Tích hợp AI Gemini & Telegram Bot](#4-tích-hợp-ai-gemini--telegram-bot)
   - [5. Hướng dẫn cài đặt & Chạy Backend](#5-hướng-dẫn-cài-đặt--chạy-backend)
   - [6. Danh mục API Endpoints](#6-danh-mục-api-endpoints)

2. [ENGLISH VERSION](#ii-english-version)
   - [1. Problem Statement & Objectives](#1-problem-statement--objectives)
   - [2. System Architecture & Backend Stack](#2-system-architecture--backend-stack)
   - [3. Anti-Bot Scraping & Security Bypass Mechanisms](#3-anti-bot-scraping--security-bypass-mechanisms)
   - [4. Gemini AI & Telegram Bot Integration](#4-gemini-ai--telegram-bot-integration)
   - [5. Backend Setup & Run Guide](#5-backend-setup--run-guide)
   - [6. API Endpoints Reference](#6-api-endpoints-reference)

---

# I. BẢN TIẾNG VIỆT

## 1. Giới thiệu bài toán & Mục tiêu

Trong thị trường thương mại điện tử cạnh tranh cao, việc theo dõi biến động giá, phân tích đối thủ cạnh tranh và đánh giá chất lượng sản phẩm là yếu tố sống còn cho các nhà bán hàng. Tuy nhiên, các sàn TMĐT lớn như Shopee và Lazada áp dụng các lớp phòng thủ nghiêm ngặt (Cloudflare WAF, Geetest Captcha, Akamai Bot Manager, Token Fingerprinting, Hotlink Protection) khiến việc thu thập dữ liệu tự động gặp nhiều khó khăn.

Dự án E-Commerce Scraper & AI Analysis Platform (Backend) được xây dựng nhằm giải quyết triệt để các thách thức trên:
- Thu thập dữ liệu sản phẩm tự động, chính xác và ổn định theo từ khóa hoặc đường dẫn trực tiếp (Direct URL).
- Ứng dụng Google Gemini AI để tự động phân tích đánh giá của người tiêu dùng, tóm tắt ưu/nhược điểm và định giá sản phẩm thông minh (Suggested Price: Lower / Higher / Fair).
- Tự động gửi báo cáo tức thời qua Telegram Bot sau mỗi tác vụ hoàn tất.
- Cung cấp hệ thống RESTful API hoàn chỉnh hỗ trợ thống kê, quản lý sản phẩm, lọc đa chiều và xuất dữ liệu ra 3 định dạng: CSV, Excel (.xlsx), JSON (.json).

---

## 2. Kiến trúc hệ thống & Backend Stack

### 2.1. Công nghệ sử dụng
- Ngôn ngữ & Framework: Java 21, Spring Boot 3.3.5 (Spring Web, Spring Data JPA, Spring Validation).
- Cơ sở dữ liệu: PostgreSQL (kết nối qua Hibernate ORM với cơ chế tự động migration schema).
- Trình duyệt tự động hóa: Selenium WebDriver 4 (ChromeDriver) với cấu hình Stealth Anti-Detect.
- HTML Parser & HTTP Client: Jsoup, Spring WebClient (Reactive HTTP Client cho Telegram API).
- Xử lý bất đồng bộ: ThreadPoolTaskExecutor kết hợp @Async tách rời hoàn toàn khỏi Transaction Boundary để tránh lock database.
- Thư viện xuất dữ liệu: Apache POI 5.x (Excel .xlsx), OpenCSV 5.x (CSV), Jackson Databind (JSON pretty-print).

### 2.2. Cấu trúc thư mục Backend
```
Tool-E-Commerrce/
├── src/main/java/com/example/toolecommerrce/
│   ├── config/              # Cấu hình Spring (Async, WebClient, OpenAPI)
│   ├── controller/          # REST Controllers (ScrapeController, ProductController)
│   ├── dto/                 # Request & Response DTOs
│   ├── entity/              # JPA Entities (Product, ScrapeJob)
│   ├── exception/           # Global Exception Handler & Error Codes
│   ├── repository/          # Spring Data JPA Repositories
│   └── service/             # Business Logic & Scraper Implementations
│       ├── impl/
│       │   ├── ScrapeOrchestratorServiceImpl.java
│       │   ├── ScrapeAsyncProcessor.java
│       │   ├── ShopeeScraperServiceImpl.java
│       │   ├── LazadaScraperServiceImpl.java
│       │   ├── GeminiAiServiceImpl.java
│       │   ├── TelegramServiceImpl.java
│       │   └── ProductServiceImpl.java
│       └── ...
└── src/main/resources/
    └── application.yml      # Cấu hình Datasource, Gemini, Telegram, Scraper
```

---

## 3. Cơ chế vượt rào bảo vệ & Anti-Bot Scraping

Quá trình thu thập dữ liệu từ Shopee và Lazada trải qua nhiều thách thức an ninh. Backend đã được thiết kế theo mô hình 4 tầng chịu lỗi (4-Tier Resilient Engine):

### 3.1. Các thử nghiệm và hạn chế ban đầu
- Direct HTTP / Jsoup Request: Thất bại do Cloudflare WAF của sàn phát hiện User-Agent Java và thiếu token bảo mật af-ac-enc-dat, trả về mã lỗi HTTP 403 Forbidden.
- Standard Headless Selenium: Thất bại do sàn phát hiện cờ tự động navigator.webdriver = true và thiếu Canvas Fingerprint, kích hoạt Geetest Slider Captcha.
- Chặn ảnh Hotlink 403: Link ảnh CDN Shopee bị vỡ khi hiển thị trên giao diện do máy chủ kiểm tra header Referer ngoại vi.

### 3.2. Giải pháp 4 tầng hiện tại
1. Tầng 1 - Stealth Driver Automation (Lazada & Direct URL):
   - Selenium ChromeDriver loại bỏ hoàn toàn cờ navigator.webdriver bằng tham số --disable-blink-features=AutomationControlled.
   - Giả lập User-Agent của Google Chrome chính thức trên Windows.
   - Hỗ trợ nạp User Profile Chrome có sẵn để mang theo Session và Cookie hợp lệ.
   - Chèn khoảng nghỉ ngẫu nhiên (Human-like Jitter Delay) mô phỏng thao tác người dùng thật.

2. Tầng 2 - Search Engine Intelligence Fallback (Vượt Captcha Shopee):
   - Khi tìm kiếm từ khóa trên Shopee bị chặn bởi Geetest Captcha, hệ thống tự động kích hoạt cơ chế truy vấn qua Search Engine công cộng (Yahoo / Bing) với cú pháp site:shopee.vn/ <keyword>.
   - Bóc tách đường link sản phẩm thật và metadata đã được sàn index công khai, loại bỏ hoàn toàn nguy cơ bị khóa IP hoặc vướng Captcha.

3. Tầng 3 - Semantic Clean Image Discovery:
   - Tự động tiền xử lý và giải mã URL Percent-Encoding (chuyển các chuỗi hex thành ký tự tiếng Việt chuẩn).
   - Truy vấn hình ảnh gốc chất lượng cao từ CDN thông qua bộ lọc loại trừ các trang ảnh stock vector (Vecteezy, Freepik).

4. Tầng 4 - Cross-Origin Referrer Stripping:
   - Cấu hình header loại bỏ Referer ngoại vi giúp hình ảnh tải về mượt mà không bị CDN chặn 403.

---

## 4. Tích hợp AI Gemini & Telegram Bot

### 4.1. Google Gemini AI Analysis
- Sử dụng mô hình Gemini qua REST API.
- Prompt được tối ưu hóa theo định dạng JSON chặt chẽ:
  - Phân tích chất lượng sản phẩm dựa trên số lượt bán và đánh giá.
  - Tóm tắt ưu điểm và nhược điểm chính của sản phẩm.
  - Tính toán mức giá hợp lý (Suggested Price) và đưa ra nhãn định hướng (Lower, Higher, Fair).

### 4.2. Telegram Notification Dispatcher
- Gửi thông báo tức thời sau khi mỗi Job hoàn thành.
- Nội dung tin nhắn gồm: Tên sản phẩm chuẩn tiếng Việt, giá bán, giá gốc, tỷ lệ giảm giá, tên shop, số lượng đã bán, đánh giá sao, tóm tắt phân tích AI, giá đề xuất và ảnh chụp sản phẩm.

---

## 5. Hướng dẫn cài đặt & Chạy Backend

### 5.1. Yêu cầu môi trường
- Java Development Kit (JDK) 21 trở lên.
- Apache Maven 3.8+.
- PostgreSQL 14+ (hoặc Cloud Database như Supabase / Neon / Railway).
- Trình duyệt Google Chrome phiên bản mới nhất.

### 5.2. Cài đặt & Khởi chạy
1. Chuyển vào thư mục Backend:
   ```bash
   cd Tool-E-Commerrce
   ```
2. Tạo file cấu hình môi trường .env hoặc cấu hình biến môi trường hệ thống:
   ```env
   DB_URL=jdbc:postgresql://localhost:5432/ecommerce_db
   DB_USERNAME=postgres
   DB_PASSWORD=your_password
   GEMINI_API_KEY=your_gemini_api_key
   TELEGRAM_BOT_TOKEN=your_telegram_bot_token
   TELEGRAM_CHAT_ID=your_telegram_chat_id
   ```
3. Biên dịch và khởi chạy Backend:
   ```bash
   mvn clean spring-boot:run
   ```
   Backend sẽ lắng nghe tại: http://localhost:8080

---

## 6. Danh mục API Endpoints

### 6.1. Thu thập dữ liệu (Scrape Jobs)
- POST /api/scrape/start : Khởi tạo tác vụ cào dữ liệu mới (bất đồng bộ).
- GET /api/scrape/jobs : Lấy danh sách tất cả các tác vụ cào.
- GET /api/scrape/jobs/{id} : Lấy chi tiết tiến độ của một tác vụ theo UUID.

### 6.2. Quản lý sản phẩm (Products)
- GET /api/products : Lấy danh sách sản phẩm phân trang với bộ lọc (keyword, category, source, minPrice, maxPrice, minRating, page, size).
- GET /api/products/{id} : Lấy thông tin chi tiết một sản phẩm.
- DELETE /api/products/{id} : Xóa vĩnh viễn một sản phẩm khỏi cơ sở dữ liệu.
- GET /api/products/categories : Lấy danh sách tất cả các danh mục sản phẩm.
- GET /api/products/stats : Lấy số liệu thống kê tổng quan (Tổng sản phẩm, Hoàn thành, Thất bại, Tổng Job).
- GET /api/products/export?format=csv|excel|json : Xuất dữ liệu sản phẩm dưới dạng CSV, Excel hoặc JSON.

---

# II. ENGLISH VERSION

## 1. Problem Statement & Objectives

In the competitive e-commerce landscape, real-time price monitoring, competitor benchmarking, and product sentiment analysis are vital for retailers. However, major marketplaces like Shopee and Lazada implement rigorous defensive measures (Cloudflare WAF, Geetest Captchas, Akamai Bot Managers, Browser Fingerprinting, and Hotlink Protection) that block traditional web scraping.

The E-Commerce Scraper & AI Analysis Platform (Backend) was engineered to address these challenges:
- Automated, resilient, and accurate data extraction by search keyword or Direct Product URL.
- Google Gemini AI integration for automated sentiment summarization, feature analysis, and market price suggestions (Lower, Higher, Fair).
- Instant Telegram Bot notifications upon scrape job completion.
- Complete RESTful API supporting dashboard analytics, multi-parameter product catalog filtering, record deletion, and multi-format data export (CSV, Excel .xlsx, JSON .json).

---

## 2. System Architecture & Backend Stack

### 2.1. Technologies Used
- Language & Framework: Java 21, Spring Boot 3.3.5 (Spring Web, Spring Data JPA, Spring Validation).
- Database: PostgreSQL (managed via Hibernate ORM with auto-updating schema).
- Browser Automation: Selenium WebDriver 4 (ChromeDriver) with stealth anti-detection flags.
- HTML Parser & HTTP Client: Jsoup, Spring WebClient (Reactive non-blocking client for Telegram API).
- Asynchronous Execution: ThreadPoolTaskExecutor with @Async isolated from Transaction Boundaries to eliminate deadlocks.
- Export Processors: Apache POI 5.x (Excel), OpenCSV 5.x (CSV), Jackson Databind (JSON pretty-print).

---

## 3. Anti-Bot Scraping & Security Bypass Mechanisms

### 3.1. Prior Trial Failures
- Direct HTTP / Jsoup Scraping: Failed immediately against Cloudflare WAF due to missing browser headers and signature tokens, returning HTTP 403 Forbidden.
- Standard Headless Selenium: Detected via navigator.webdriver = true flag and absence of canvas fingerprinting, triggering Geetest Slider Captchas.
- Hotlink Protection (Image 403): CDN endpoints blocked cross-origin requests by inspecting the Referer header.

### 3.2. Current 4-Tier Architecture
1. Tier 1 - Stealth Driver Automation (Lazada & Direct URLs):
   - Strips automation flags via --disable-blink-features=AutomationControlled.
   - Mimics genuine Windows Chrome User-Agent strings.
   - Reuses existing authenticated Chrome User Profiles with active cookies.
   - Employs human-like randomized delays between actions.

2. Tier 2 - Search Engine Intelligence Fallback (Shopee Captcha Bypass):
   - Bypasses marketplace on-site search barriers by querying public search indexers (Yahoo / Bing) via site:shopee.vn/ <keyword>.
   - Extracts publicly indexed canonical URLs and metadata with zero risk of IP blacklisting or captcha interruptions.

3. Tier 3 - Semantic Clean Image Discovery:
   - Sanitizes and decodes URL percent-encoding.
   - Retrieves high-resolution product imagery while filtering out generic stock vectors (Vecteezy, Freepik).

4. Tier 4 - Cross-Origin Referrer Stripping:
   - Configures headers to prevent CDN hotlink rejections.

---

## 4. Gemini AI & Telegram Bot Integration

### 4.1. Google Gemini AI Analysis
- Connected via REST API.
- Prompt structured to enforce structured JSON output:
  - Quality and sentiment summarization based on ratings and review counts.
  - Identification of key pros and cons.
  - Fair market price calculation and price positioning label (Lower, Higher, Fair).

### 4.2. Telegram Notification Dispatcher
- Dispatches real-time summary alerts upon job completion.
- Message payload: Formatted product name, sale price, original price, discount percentage, merchant name, units sold, star rating, AI analysis summary, suggested price, and product image attachment.

---

## 5. Backend Setup & Run Guide

### 5.1. Prerequisites
- Java Development Kit (JDK) 21 or higher.
- Apache Maven 3.8+.
- PostgreSQL 14+ (local or managed cloud instance like Supabase/Neon).
- Latest Google Chrome browser.

### 5.2. Setup & Execution
1. Navigate to the backend directory:
   ```bash
   cd Tool-E-Commerrce
   ```
2. Configure environment variables in .env or system environment:
   ```env
   DB_URL=jdbc:postgresql://localhost:5432/ecommerce_db
   DB_USERNAME=postgres
   DB_PASSWORD=your_password
   GEMINI_API_KEY=your_gemini_api_key
   TELEGRAM_BOT_TOKEN=your_telegram_bot_token
   TELEGRAM_CHAT_ID=your_telegram_chat_id
   ```
3. Build and run the backend:
   ```bash
   mvn clean spring-boot:run
   ```
   Backend will start at: http://localhost:8080

---

## 6. API Endpoints Reference

### 6.1. Scrape Operations
- POST /api/scrape/start : Launch a new asynchronous scraping job.
- GET /api/scrape/jobs : Retrieve all scraping jobs.
- GET /api/scrape/jobs/{id} : Get status and progress for a specific job UUID.

### 6.2. Product Operations
- GET /api/products : Paginated product list with multi-parameter filtering (keyword, category, source, minPrice, maxPrice, minRating, page, size).
- GET /api/products/{id} : Retrieve product details by UUID.
- DELETE /api/products/{id} : Delete a product record permanently.
- GET /api/products/categories : Retrieve all distinct product categories.
- GET /api/products/stats : Dashboard metrics (Total Products, Completed, Failed, Total Jobs).
- GET /api/products/export?format=csv|excel|json : Export product records in CSV, Excel, or JSON format.
