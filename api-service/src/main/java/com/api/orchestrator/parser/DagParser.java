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

    /**
     * Parse YAML or JSON content into DagDefinition (POJOs).
     * The method accepts either YAML or JSON that maps to DagDefinition.
     */
    public DagDefinition parseDefinition(String yamlOrJson) throws Exception {
        // The YAMLFactory ObjectMapper will also parse JSON fine.
        return yamlMapper.readValue(yamlOrJson, DagDefinition.class);
    }

    /**
     * Build a JGraphT DirectedAcyclicGraph from DagDefinition.
     * Throws IllegalArgumentException if graph contains cycles or missing references.
     */
    public DirectedAcyclicGraph<String, DefaultEdge> buildGraph(DagDefinition def) {
        if (def == null) throw new IllegalArgumentException("DagDefinition is null");
        if (def.getTasks() == null) throw new IllegalArgumentException("No tasks defined in DAG");

        DirectedAcyclicGraph<String, DefaultEdge> dag =
                new DirectedAcyclicGraph<>(DefaultEdge.class);

        // Add all task ids as vertices
        Set<String> taskIds = new HashSet<>();
        for (TaskDef t : def.getTasks()) {
            if (t.getId() == null || t.getId().isBlank()) {
                throw new IllegalArgumentException("Each task must have a non-empty id");
            }
            if (!taskIds.add(t.getId())) {
                throw new IllegalArgumentException("Duplicate task id: " + t.getId());
            }
            dag.addVertex(t.getId());
        }

        // Add edges for dependencies: for each task T, for each dependency D in depends_on, add edge D -> T
        for (TaskDef t : def.getTasks()) {
            List<String> deps = t.getDepends_on();
            if (deps == null) continue;
            for (String dep : deps) {
                if (!dag.containsVertex(dep)) {
                    throw new IllegalArgumentException("Task '" + t.getId() + "' depends on unknown task '" + dep + "'");
                }
                // add edge from dep -> t
                dag.addEdge(dep, t.getId());
            }
        }

        // Detect cycles (should not happen with DirectedAcyclicGraph but double-check)
        CycleDetector<String, DefaultEdge> detector = new CycleDetector<>(dag);
        if (detector.detectCycles()) {
            Set<String> cyc = detector.findCycles();
            throw new IllegalArgumentException("Cycle detected in DAG: " + cyc);
        }

        return dag;
    }

    /**
     * Returns tasks in topological order (ready-to-execute order)
     */
    public List<String> topologicalOrder(DirectedAcyclicGraph<String, DefaultEdge> dag) {
        List<String> ordered = new ArrayList<>();
        TopologicalOrderIterator<String, DefaultEdge> it = new TopologicalOrderIterator<>(dag);
        while (it.hasNext()) ordered.add(it.next());
        return ordered;
    }
}
