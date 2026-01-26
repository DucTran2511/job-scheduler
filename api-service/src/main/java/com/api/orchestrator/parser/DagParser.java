package com.api.orchestrator.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.common.dto.DagDefinition;
import com.common.dto.TaskDef;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class DagParser {

    private final ObjectMapper yamlMapper;

    public DagParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public DagDefinition parseDefinition(String yamlOrJson) throws Exception {
        return yamlMapper.readValue(yamlOrJson, DagDefinition.class);
    }

    public DirectedAcyclicGraph<String, DefaultEdge> buildGraph(DagDefinition def) {
        if (def == null)
            throw new IllegalArgumentException("DagDefinition is null");
        if (def.getTasks() == null)
            throw new IllegalArgumentException("No tasks defined in DAG");

        DirectedAcyclicGraph<String, DefaultEdge> dag = new DirectedAcyclicGraph<>(DefaultEdge.class);

        Set<String> taskIds = new HashSet<>();
        for (TaskDef t : def.getTasks()) {
            if (t.getId() == null || t.getId().isBlank()) {
                throw new IllegalArgumentException("Each task must have a non-empty id");
            }
            if (!taskIds.add(t.getId())) {
                throw new IllegalArgumentException("Duplicate task id: " + t.getId());
            }
            validateTask(t);
            dag.addVertex(t.getId());
        }

        for (TaskDef t : def.getTasks()) {
            List<String> deps = t.getDepends_on();
            if (deps == null)
                continue;
            for (String dep : deps) {
                if (!dag.containsVertex(dep)) {
                    throw new IllegalArgumentException(
                            "Task '" + t.getId() + "' depends on unknown task '" + dep + "'");
                }
                try {
                    dag.addEdge(dep, t.getId());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Cycle detected in DAG: " + e.getMessage());
                }
            }
        }

        CycleDetector<String, DefaultEdge> detector = new CycleDetector<>(dag);
        if (detector.detectCycles()) {
            Set<String> cyc = detector.findCycles();
            throw new IllegalArgumentException("Cycle detected in DAG: " + cyc);
        }

        return dag;
    }

    public List<String> topologicalOrder(DirectedAcyclicGraph<String, DefaultEdge> dag) {
        List<String> ordered = new ArrayList<>();
        TopologicalOrderIterator<String, DefaultEdge> it = new TopologicalOrderIterator<>(dag);
        while (it.hasNext())
            ordered.add(it.next());
        return ordered;
    }

    private void validateTask(TaskDef t) {
        String type = t.getTaskType();
        boolean isShell = type == null || type.trim().isEmpty() || "SHELL".equalsIgnoreCase(type);

        if (isShell) {
            if (t.getCommand() == null || t.getCommand().trim().isEmpty()) {
                throw new IllegalArgumentException("Task '" + t.getId() + "' of type SHELL must have a command");
            }
        }
    }
}
