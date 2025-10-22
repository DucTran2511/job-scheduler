//package com.api.controller;
//
//import com.api.entity.JobEntity;
//import com.api.service.JobService;
//import com.common.dto.JobRequest;
//import jakarta.validation.Valid;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/jobs")
//@RequiredArgsConstructor
//public class JobController {
//
//    private final JobService jobService;
//
//    @PostMapping
//    public ResponseEntity<JobEntity> createJob(@Valid @RequestBody JobRequest request) {
//        JobEntity job = jobService.createJob(request);
//        return ResponseEntity.ok(job);
//    }
//
//    @GetMapping
//    public ResponseEntity<List<JobEntity>> getAllJobs() {
//        return ResponseEntity.ok(jobService.getAllJobs());
//    }
//
//    @GetMapping("/{id}")
//    public ResponseEntity<JobEntity> getJobById(@PathVariable String id) {
//        JobEntity job = jobService.getJobById(id);
//        return (job != null) ? ResponseEntity.ok(job) : ResponseEntity.notFound().build();
//    }
//}
//
