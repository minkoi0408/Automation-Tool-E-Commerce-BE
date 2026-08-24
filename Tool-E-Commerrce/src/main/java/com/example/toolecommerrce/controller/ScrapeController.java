package com.example.toolecommerrce.controller;

import com.example.toolecommerrce.dto.response.ApiResponse;
import com.example.toolecommerrce.dto.request.ScrapeRequest;
import com.example.toolecommerrce.dto.response.ScrapeJobResponse;
import com.example.toolecommerrce.service.ScrapeOrchestratorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/scrape")
@RequiredArgsConstructor
public class ScrapeController {

    private final ScrapeOrchestratorService scrapeOrchestratorService;

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<UUID>> startScrape(@Valid @RequestBody ScrapeRequest request) {
        UUID jobId = scrapeOrchestratorService.startScrapeJob(request);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.<UUID>builder()
                        .success(true)
                        .message("Job crawl đã được tạo và đang xử lý")
                        .data(jobId)
                        .build());
    }

    @GetMapping("/jobs")
    public ResponseEntity<ApiResponse<List<ScrapeJobResponse>>> getAllJobs() {
        return ResponseEntity.ok(ApiResponse.<List<ScrapeJobResponse>>builder()
                .success(true)
                .message("Thành công")
                .data(scrapeOrchestratorService.getAllJobs())
                .build());
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<ApiResponse<ScrapeJobResponse>> getJobById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.<ScrapeJobResponse>builder()
                .success(true)
                .message("Thành công")
                .data(scrapeOrchestratorService.getJobById(id))
                .build());
    }
}
