package com.example.toolecommerrce.service;

import com.example.toolecommerrce.dto.response.ScrapeJobResponse;
import com.example.toolecommerrce.dto.request.ScrapeRequest;

import java.util.List;
import java.util.UUID;

public interface ScrapeOrchestratorService {

    UUID startScrapeJob(ScrapeRequest request);

    List<ScrapeJobResponse> getAllJobs();

    ScrapeJobResponse getJobById(UUID id);
}
