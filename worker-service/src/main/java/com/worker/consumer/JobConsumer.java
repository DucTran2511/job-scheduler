package com.worker.consumer;

import com.common.model.Job;
import com.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import com.worker.executor.TaskExecutor;

@Component
@RequiredArgsConstructor
public class JobConsumer implements MessageListener {

    private final TaskExecutor taskExecutor;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String jobJson = new String(message.getBody());
        Job job = JsonUtils.fromJson(jobJson, Job.class);
        System.out.println("📥 Worker received job: " + job.getId() + " → " + job.getTask());

        taskExecutor.execute(job);
    }
}
