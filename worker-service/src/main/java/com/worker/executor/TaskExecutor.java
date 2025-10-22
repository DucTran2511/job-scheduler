package com.worker.executor;

import com.common.model.Job;
import org.springframework.stereotype.Component;

@Component
public class TaskExecutor {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    public void execute(Job job) {
        int attempt = 0;
        boolean success = false;
        String lastError = null;

        while (attempt < MAX_RETRIES && !success) {
            try {
                attempt++;
                System.out.println("🚀 Executing job " + job.getId() + ": " + job.getTask() + " (attempt " + attempt + ")");

                performTask(job);

                System.out.println("✅ Job " + job.getId() + " completed successfully.");
                success = true;

            } catch (Exception e) {
                lastError = e.getMessage();
                System.err.println("❌ Job " + job.getId() + " failed on attempt " + attempt + ": " + lastError);

                if (attempt < MAX_RETRIES) {
                    System.out.println("⏳ Retrying in " + RETRY_DELAY_MS + " ms...");
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.err.println("⚠️ Retry interrupted for job " + job.getId());
                        break;
                    }
                } else {
                    System.err.println("🚫 Job " + job.getId() + " failed permanently after " + MAX_RETRIES + " attempts.");
                }
            }
        }

        job.setRetryCount(attempt);
        job.setLastError(lastError);
    }

    private void performTask(Job job) throws Exception {

        if (Math.random() < 0.3) {
            throw new RuntimeException("Simulated random failure");
        }

        Thread.sleep(1500);
    }
}

