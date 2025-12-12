package com.api.cache;

import com.common.dto.DagDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.common.dto.TaskDef;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
@RequiredArgsConstructor
public class RedisDagStore {

    private final StringRedisTemplate redis;

    public void saveDag(String workflowId, DagDefinition dag) {
        Map<String, String> deps = new HashMap<>();
        for (TaskDef task : dag.getTasks()) {
            deps.put(task.getId(),
                    (task.getDepends_on() == null || task.getDepends_on().isEmpty())
                            ? ""
                            : String.join(",", task.getDepends_on()));
        }


        // Store DAG structure
        redis.opsForHash().putAll("dag:" + workflowId + ":graph", deps);

        // Store metadata (as separate fields)
        redis.opsForHash().put("dag:" + workflowId + ":metadata", "status", "RUNNING");
        redis.opsForHash().put("dag:" + workflowId + ":metadata", "createdAt", LocalDateTime.now().toString());
    }


    public void setTaskStatus(String workflowId, String taskId, String status) {
        redis.opsForHash().putAll("task:" + workflowId + ":" + taskId, Map.of(
                "status", status,
                "updatedAt", LocalDateTime.now().toString()
        ));
    }

    public List<String> getReadyTasks(String workflowId) {
        Map<Object, Object> graph = redis.opsForHash().entries("dag:" + workflowId + ":graph");
        List<String> ready = new ArrayList<>();
        for (Map.Entry<Object, Object> e : graph.entrySet()) {
            String task = (String) e.getKey();
            String deps = (String) e.getValue();
            if (deps.isEmpty()) {
                ready.add(task);
            } else {
                boolean allDone = Arrays.stream(deps.split(","))
                        .allMatch(d -> "SUCCESS".equals(
                                redis.<String, String>opsForHash()
                                        .get("task:" + workflowId + ":" + d, "status")));
                if (allDone) ready.add(task);
            }
        }
        return ready;
    }

    public Map<String, List<String>> loadDag(String workflowId) {
        Map<Object, Object> entries = redis.opsForHash().entries("dag:" + workflowId + ":graph");
        Map<String, List<String>> graph = new HashMap<>();
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            String taskId = (String) e.getKey();
            String deps = (String) e.getValue();
            graph.put(taskId, deps.isEmpty() ? List.of() : List.of(deps.split(",")));
        }
        return graph;
    }

    public void deleteDag(String workflowId) {
        redis.delete("dag:" + workflowId + ":graph");
        redis.delete("dag:" + workflowId);
    }

}
