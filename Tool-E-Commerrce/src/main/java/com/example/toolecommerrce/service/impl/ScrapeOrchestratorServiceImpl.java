package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.dto.AiAnalysisResult;
import com.example.toolecommerrce.dto.request.ScrapeRequest;
import com.example.toolecommerrce.dto.response.ScrapeJobResponse;
import com.example.toolecommerrce.entity.Product;
import com.example.toolecommerrce.entity.ScrapeJob;
import com.example.toolecommerrce.exception.AppException;
import com.example.toolecommerrce.exception.ErrorCode;
import com.example.toolecommerrce.repository.ProductRepository;
import com.example.toolecommerrce.repository.ScrapeJobRepository;
import com.example.toolecommerrce.service.GeminiAiService;
import com.example.toolecommerrce.service.ScrapeOrchestratorService;
import com.example.toolecommerrce.service.ShopeeScraperService;
import com.example.toolecommerrce.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScrapeOrchestratorServiceImpl implements ScrapeOrchestratorService {

    // ✅ Đúng layer: chỉ inject Service, KHÔNG inject Repository trực tiếp
    // Ngoại lệ: ScrapeJobRepository và ProductRepository inject trực tiếp vì
    // ScrapeOrchestratorService chính là service owner của 2 entity này
    private final ScrapeJobRepository scrapeJobRepository;
    private final ProductRepository productRepository;
    private final ShopeeScraperService shopeeScraperService;
    private final GeminiAiService geminiAiService;
    private final TelegramService telegramService;

    @Override
    @Transactional
    public UUID startScrapeJob(ScrapeRequest request) {
        ScrapeJob job = ScrapeJob.builder()
                .inputType(request.getInputType())
                .inputValue(request.getInputValue())
                .status(ScrapeJob.JobStatus.PENDING)
                .processedProducts(0)
                .failedProducts(0)
                .build();
        job = scrapeJobRepository.save(job);

        log.info("[Orchestrator] Created ScrapeJob {} for input: {}", job.getId(), request.getInputValue());
        processJobAsync(job.getId(), request);
        return job.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScrapeJobResponse> getAllJobs() {
        return scrapeJobRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toScrapeJobResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ScrapeJobResponse getJobById(UUID id) {
        ScrapeJob job = scrapeJobRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.SCRAPE_JOB_NOT_FOUND));
        return toScrapeJobResponse(job);
    }

    // -------------------------------------------------------------------------
    // Async processing
    // -------------------------------------------------------------------------

    @Async
    public void processJobAsync(UUID jobId, ScrapeRequest request) {
        ScrapeJob job = scrapeJobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        try {
            job.setStatus(ScrapeJob.JobStatus.RUNNING);
            scrapeJobRepository.save(job);

            telegramService.sendMessage("🚀 Bắt đầu crawl: <b>" + request.getInputValue() + "</b>");

            if (request.getInputType() == ScrapeJob.InputType.URL) {
                processSingleUrl(job, request.getInputValue());
            } else {
                processKeyword(job, request.getInputValue(), request.getMaxProducts());
            }

            job.setStatus(ScrapeJob.JobStatus.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            scrapeJobRepository.save(job);

            telegramService.sendMessage(
                    String.format("✅ Hoàn thành crawl! Tổng: %d sản phẩm, Thành công: %d, Lỗi: %d",
                            job.getTotalProducts(), job.getProcessedProducts(), job.getFailedProducts())
            );

        } catch (Exception e) {
            log.error("[Orchestrator] Job {} failed: {}", jobId, e.getMessage());
            job.setStatus(ScrapeJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            scrapeJobRepository.save(job);
            telegramService.sendMessage("❌ Crawl thất bại: " + e.getMessage());
        }
    }

    private void processSingleUrl(ScrapeJob job, String url) {
        job.setTotalProducts(1);
        scrapeJobRepository.save(job);
        try {
            Product product = shopeeScraperService.scrapeByUrl(url);
            product = enrichWithAi(product);
            product.setScrapeJob(job);
            productRepository.save(product);
            job.setProcessedProducts(1);
            scrapeJobRepository.save(job);
            telegramService.sendProductSummary(product);
            log.info("[Orchestrator] Successfully processed URL: {}", url);
        } catch (Exception e) {
            log.error("[Orchestrator] Failed to process URL {}: {}", url, e.getMessage());
            job.setFailedProducts(1);
            scrapeJobRepository.save(job);
        }
    }

    private void processKeyword(ScrapeJob job, String keyword, int maxProducts) {
        List<String> urls = shopeeScraperService.searchProductUrls(keyword, maxProducts);
        job.setTotalProducts(urls.size());
        scrapeJobRepository.save(job);

        int processed = 0, failed = 0;
        for (String url : urls) {
            try {
                Product product = shopeeScraperService.scrapeByUrl(url);
                product = enrichWithAi(product);
                product.setScrapeJob(job);
                productRepository.save(product);
                processed++;
                job.setProcessedProducts(processed);
                scrapeJobRepository.save(job);
                telegramService.sendProductSummary(product);
                Thread.sleep((long) (Math.random() * 2000 + 1000));
            } catch (Exception e) {
                log.error("[Orchestrator] Failed to process URL {}: {}", url, e.getMessage());
                failed++;
                job.setFailedProducts(failed);
                scrapeJobRepository.save(job);
            }
        }
    }

    private Product enrichWithAi(Product product) {
        try {
            AiAnalysisResult result = geminiAiService.analyzeProduct(product);
            product.setAiAnalysis(result.getRawJson());
            product.setAiSuggestedPrice(result.getSuggestedPrice());
            product.setAiSummary(result.getQualitySummary());
            product.setStatus(Product.ProductStatus.COMPLETED);
        } catch (Exception e) {
            log.error("[Orchestrator] AI enrichment failed for {}: {}", product.getName(), e.getMessage());
            product.setStatus(Product.ProductStatus.FAILED);
        }
        return product;
    }

    // -------------------------------------------------------------------------
    // Private mapping (Entity -> Response) - logic nằm ở service
    // -------------------------------------------------------------------------

    private ScrapeJobResponse toScrapeJobResponse(ScrapeJob job) {
        int progress = 0;
        if (job.getTotalProducts() != null && job.getTotalProducts() > 0
                && job.getProcessedProducts() != null) {
            progress = (int) Math.round(
                    (double) job.getProcessedProducts() / job.getTotalProducts() * 100
            );
        }
        if (job.getStatus() == ScrapeJob.JobStatus.COMPLETED) progress = 100;

        return ScrapeJobResponse.builder()
                .id(job.getId())
                .inputType(job.getInputType())
                .inputValue(job.getInputValue())
                .status(job.getStatus())
                .totalProducts(job.getTotalProducts())
                .processedProducts(job.getProcessedProducts())
                .failedProducts(job.getFailedProducts())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .completedAt(job.getCompletedAt())
                .progressPercent(progress)
                .build();
    }
}
