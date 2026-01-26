package com.worker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestratorCallbackService {

    private final RestTemplate restTemplate;

    @Value("${orchestrator.callback.url:http://localhost:8080/api/workflows/task/callback}")
    private String callbackUrl;

    @Value("${orchestrator.callback.retries:3}")
    private int maxRetries;

    @Value("${orchestrator.callback.retry-delay-ms:1000}")
    private long retryDelayMs;

    public void reportTaskCompletion(String workflowRunId, String taskId, boolean success, String errorMessage) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("workflowRunId", workflowRunId);
        payload.put("taskId", taskId);
        payload.put("success", success);
        payload.put("lastError", errorMessage);

        reportWithRetry(payload);
    }

    private void reportWithRetry(Map<String, Object> payload) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                log.debug("Reporting task completion to orchestrator (attempt {}/{}): {}",
                        attempt, maxRetries, payload.get("taskId"));

                ResponseEntity<Void> response = restTemplate.postForEntity(
                        callbackUrl,
                        payload,
                        Void.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("Successfully reported task {} completion to orchestrator",
                            payload.get("taskId"));
                    return;
                }

                log.warn("Orchestrator returned non-2xx status: {}", response.getStatusCode());

            } catch (RestClientException e) {
                lastException = e;
                log.error("Failed to report to orchestrator (attempt {}/{}): {}",
                        attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Retry interrupted for task {}", payload.get("taskId"));
                        break;
                    }
                }
            }
        }

        log.error("Failed to report task {} completion after {} attempts. Last error: {}",
                payload.get("taskId"), maxRetries,
                lastException != null ? lastException.getMessage() : "Unknown");
    }
}
