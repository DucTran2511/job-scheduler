package com.worker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionContext {

    private String workflowRunId;

    private String taskId;

    private String taskType;

    @Builder.Default
    private Map<String, Object> config = new HashMap<>();

    private Long timeout;

    private String workingDirectory;

    @Builder.Default
    private Map<String, String> environment = new HashMap<>();

    public String getConfigString(String key) {
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }

    public String getConfigString(String key, String defaultValue) {
        String value = getConfigString(key);
        return value != null ? value : defaultValue;
    }
}
