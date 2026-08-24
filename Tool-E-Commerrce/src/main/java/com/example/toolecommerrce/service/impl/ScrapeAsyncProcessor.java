package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.dto.response.AiAnalysisResultResponse;
import com.example.toolecommerrce.dto.request.ScrapeRequest;
import com.example.toolecommerrce.entity.Product;
import com.example.toolecommerrce.entity.ScrapeJob;
import com.example.toolecommerrce.repository.ProductRepository;
import com.example.toolecommerrce.repository.ScrapeJobRepository;
import com.example.toolecommerrce.service.GeminiAiService;
import com.example.toolecommerrce.service.LazadaScraperService;
import com.example.toolecommerrce.service.ShopeeScraperService;
import com.example.toolecommerrce.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Class riêng biệt để @Async hoạt động đúng.
 * Spring AOP chỉ intercept khi gọi từ bean khác (không tự gọi trong cùng class).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScrapeAsyncProcessor {

    private final ScrapeJobRepository scrapeJobRepository;
    private final ProductRepository productRepository;
    private final ShopeeScraperService shopeeScraperService;
    private final LazadaScraperService lazadaScraperService;
    private final GeminiAiService geminiAiService;
    private final TelegramService telegramService;

    @Async
    public void processJobAsync(java.util.UUID jobId, ScrapeRequest request) {
        ScrapeJob job = scrapeJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("[Async] ScrapeJob {} not found in database", jobId);
            return;
        }

        try {
            job.setStatus(ScrapeJob.JobStatus.RUNNING);
            scrapeJobRepository.save(job);

            telegramService.sendMessage("🚀 Bắt đầu crawl: <b>" + request.getInputValue() + "</b>");

            if (request.getInputType() == ScrapeJob.InputType.URL) {
                // Auto-detect platform từ URL
                String url = request.getInputValue();
                if (url.contains("lazada.vn")) {
                    processSingleUrl(job, url, com.example.toolecommerrce.entity.Product.ProductSource.LAZADA);
                } else {
                    processSingleUrl(job, url, com.example.toolecommerrce.entity.Product.ProductSource.SHOPEE);
                }
            } else {
                processKeyword(job, request.getInputValue(), request.getMaxProducts(), request.getPlatform());
            }

            // Nếu job đã bị set FAILED trong processKeyword thì không ghi đè thành COMPLETED
            if (job.getStatus() == ScrapeJob.JobStatus.FAILED) {
                telegramService.sendMessage("❌ Crawl thất bại: " + job.getErrorMessage());
                return;
            }

            job.setStatus(ScrapeJob.JobStatus.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            scrapeJobRepository.save(job);

            telegramService.sendMessage(
                    String.format("✅ Hoàn thành crawl! Tổng: %d, Thành công: %d, Lỗi: %d",
                            job.getTotalProducts(), job.getProcessedProducts(), job.getFailedProducts())
            );

        } catch (Exception e) {
            log.error("[Async] Job {} failed: {}", jobId, e.getMessage());
            job.setStatus(ScrapeJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            scrapeJobRepository.save(job);
            telegramService.sendMessage("❌ Crawl thất bại: " + e.getMessage());
        }
    }

    private void processSingleUrl(ScrapeJob job, String url,
                                   com.example.toolecommerrce.entity.Product.ProductSource source) {
        job.setTotalProducts(1);
        scrapeJobRepository.save(job);
        try {
            Product product;
            if (source == com.example.toolecommerrce.entity.Product.ProductSource.LAZADA) {
                product = lazadaScraperService.scrapeByUrl(url);
            } else {
                product = shopeeScraperService.scrapeByUrl(url);
            }
            product = enrichWithAi(product);
            product.setScrapeJob(job);
            productRepository.save(product);
            job.setProcessedProducts(1);
            scrapeJobRepository.save(job);
            telegramService.sendProductSummary(product);
            log.info("[Async] Successfully processed URL: {}", url);
        } catch (Exception e) {
            log.error("[Async] Failed to process URL {}: {}", url, e.getMessage());
            job.setFailedProducts(1);
            scrapeJobRepository.save(job);
        }
    }

    private void processKeyword(ScrapeJob job, String keyword, int maxProducts,
                                com.example.toolecommerrce.dto.request.ScrapeRequest.Platform platform) {
        log.info("[Async] Searching keyword='{}', platform={}, max={}", keyword, platform, maxProducts);

        List<String> shopeeUrls = new java.util.ArrayList<>();
        List<String> lazadaUrls = new java.util.ArrayList<>();

        if (platform == com.example.toolecommerrce.dto.request.ScrapeRequest.Platform.SHOPEE
                || platform == com.example.toolecommerrce.dto.request.ScrapeRequest.Platform.ALL) {
            shopeeUrls = shopeeScraperService.searchProductUrls(keyword, maxProducts);
            log.info("[Async] Shopee: {} URLs", shopeeUrls.size());
        }
        if (platform == com.example.toolecommerrce.dto.request.ScrapeRequest.Platform.LAZADA
                || platform == com.example.toolecommerrce.dto.request.ScrapeRequest.Platform.ALL) {
            lazadaUrls = lazadaScraperService.searchProductUrls(keyword, maxProducts);
            log.info("[Async] Lazada: {} URLs", lazadaUrls.size());
        }

        if (shopeeUrls.isEmpty() && lazadaUrls.isEmpty()) {
            log.warn("[Async] No results for keyword '{}' on platform {}", keyword, platform);
            job.setStatus(ScrapeJob.JobStatus.FAILED);
            job.setErrorMessage("Không tìm được sản phẩm cho keyword: " + keyword);
            scrapeJobRepository.save(job);
            return;
        }

        int total = shopeeUrls.size() + lazadaUrls.size();
        job.setTotalProducts(total);
        scrapeJobRepository.save(job);

        int processed = 0, failed = 0;

        // Scrape Shopee
        for (String url : shopeeUrls) {
            try {
                Product product = shopeeScraperService.scrapeByUrl(url);
                product = enrichWithAi(product);
                product.setScrapeJob(job);
                productRepository.save(product);
                processed++;
                job.setProcessedProducts(processed);
                scrapeJobRepository.save(job);
                telegramService.sendProductSummary(product);
                Thread.sleep((long) (Math.random() * 2000 + 1500));
            } catch (Exception e) {
                log.error("[Async] Shopee URL failed {}: {}", url, e.getMessage());
                failed++;
                job.setFailedProducts(failed);
                scrapeJobRepository.save(job);
            }
        }

        // Scrape Lazada
        for (String url : lazadaUrls) {
            try {
                Product product = lazadaScraperService.scrapeByUrl(url);
                if (product == null || product.getStatus() == Product.ProductStatus.FAILED
                        || product.getPrice() == null
                        || "Sản phẩm Lazada".equals(product.getName())) {
                    log.warn("[Async] Lazada product scrape was invalid/failed for URL: {}", url);
                    failed++;
                    job.setFailedProducts(failed);
                    scrapeJobRepository.save(job);
                    continue;
                }
                product = enrichWithAi(product);
                product.setScrapeJob(job);
                productRepository.save(product);
                processed++;
                job.setProcessedProducts(processed);
                scrapeJobRepository.save(job);
                telegramService.sendProductSummary(product);
                Thread.sleep((long) (Math.random() * 2000 + 1500));
            } catch (Exception e) {
                log.error("[Async] Lazada URL failed {}: {}", url, e.getMessage());
                failed++;
                job.setFailedProducts(failed);
                scrapeJobRepository.save(job);
            }
        }
    }

    private Product enrichWithAi(Product product) {
        try {
            AiAnalysisResultResponse result = geminiAiService.analyzeProduct(product);
            product.setAiAnalysis(result.getRawJson());
            product.setAiSuggestedPrice(result.getSuggestedPrice());
            product.setAiSummary(result.getQualitySummary());
            product.setStatus(Product.ProductStatus.COMPLETED);
        } catch (Exception e) {
            log.error("[Async] AI enrichment failed for {}: {}", product.getName(), e.getMessage());
            product.setStatus(Product.ProductStatus.FAILED);
        }
        return product;
    }
}
