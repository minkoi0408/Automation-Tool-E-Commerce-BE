package com.example.toolecommerrce.repository;

import com.example.toolecommerrce.entity.ScrapeJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ScrapeJobRepository extends JpaRepository<ScrapeJob, UUID> {

    List<ScrapeJob> findAllByOrderByCreatedAtDesc();

    List<ScrapeJob> findByStatus(ScrapeJob.JobStatus status);
}
