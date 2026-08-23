package com.example.toolecommerrce.service.impl;

import com.example.toolecommerrce.dto.request.ScrapeRequest;
import com.example.toolecommerrce.dto.response.ScrapeJobResponse;
import com.example.toolecommerrce.entity.ScrapeJob;
import com.example.toolecommerrce.exception.AppException;
import com.example.toolecommerrce.exception.ErrorCode;
import com.example.toolecommerrce.repository.ScrapeJobRepository;
import com.example.toolecommerrce.service.ScrapeOrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScrapeOrchestratorServiceImpl implements ScrapeOrchestratorService {

    private final ScrapeJobRepository scrapeJobRepository;
    private final ScrapeAsyncProcessor scrapeAsyncProcessor; // ✅ inject bean khác → @Async hoạt động đúng

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

        // Gọi qua bean khác → Spring proxy intercept được → @Async hoạt động
        scrapeAsyncProcessor.processJobAsync(job, request);

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
    // Private mapping
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
