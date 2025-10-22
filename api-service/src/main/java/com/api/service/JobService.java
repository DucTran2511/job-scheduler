//package com.api.service;
//
//import com.api.entity.JobEntity;
//import com.api.repository.JobRepository;
//import com.common.dto.JobRequest;
//import com.common.model.Job;
//import lombok.RequiredArgsConstructor;
//import org.springframework.dao.DataAccessException;
//import org.springframework.data.redis.RedisConnectionFailureException;
//import org.springframework.stereotype.Service;
//
//import java.time.LocalDateTime;
//import java.util.List;
//
//@Service
//@RequiredArgsConstructor
//
//public class JobService {
//
//    private final JobRepository jobRepository;
//    private final RedisPublisher redisPublisher;
//
//    public JobEntity createJob(JobRequest request) {
//        JobEntity job = new JobEntity();
//        job.setTask(request.getTask());
//        job.setPayload(request.getPayload());
//        job.setStatus(JobEntity.JobStatus.PENDING);
//        job.setCreatedAt(LocalDateTime.now());
//        job.setUpdatedAt(LocalDateTime.now());
//        jobRepository.save(job);
//
//        boolean success = publishWithRetry(job);
//        if (!success) {
//            job.setStatus(JobEntity.JobStatus.FAILED);
//            job.setLastError("Redis publish failed after max retries");
//        }
//        return jobRepository.save(job);
//    }
//
//    public boolean publishWithRetry(JobEntity job) {
//        int maxRetries = job.getMaxRetries();
//        for (int attempt = 1; attempt <= maxRetries; attempt++) {
//            try{
//                redisPublisher.publishJob(job.getId(), job.getTask(), job.getPayload());
//                return true;
//            } catch (DataAccessException e) {
//                job.setRetryCount(attempt);
//                job.setLastError(e.getMessage());
//                jobRepository.save(job);
//                System.err.println("⚠️ Redis publish failed (attempt " + attempt + "): " + e.getMessage());
//                try {
//                    Thread.sleep(1000L * attempt);
//                } catch (InterruptedException ie) {
//                    Thread.currentThread().interrupt();
//                    break;
//                }
//            }
//        }
//        return false;
//    }
//
//    public List<JobEntity> getAllJobs() {
//        return jobRepository.findAll();
//    }
//
//    public JobEntity getJobById(String id) {
//        return jobRepository.findById(id).orElse(null);
//    }
//}
