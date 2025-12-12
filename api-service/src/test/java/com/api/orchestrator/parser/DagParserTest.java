package com.api.orchestrator.parser;

import com.common.dto.DagDefinition;
import com.common.dto.TaskDef;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagParserTest {

    private DagParser dagParser;

    @BeforeEach
    void setUp() {
        dagParser = new DagParser();
    }

    @Test
    void parseDefinition_ShouldParseValidYaml() throws Exception {
        String yaml = """
                name: test-workflow
                description: Test workflow
                tasks:
                  - id: task1
                    name: First Task
                    command: echo hello
                  - id: task2
                    name: Second Task
                    command: echo world
                    depends_on:
                      - task1
                """;

        DagDefinition def = dagParser.parseDefinition(yaml);

        assertThat(def).isNotNull();
        assertThat(def.getName()).isEqualTo("test-workflow");
        assertThat(def.getDescription()).isEqualTo("Test workflow");
        assertThat(def.getTasks()).hasSize(2);
        assertThat(def.getTasks().get(0).getId()).isEqualTo("task1");
        assertThat(def.getTasks().get(1).getId()).isEqualTo("task2");
        assertThat(def.getTasks().get(1).getDepends_on()).containsExactly("task1");
    }

    @Test
    void parseDefinition_ShouldParseValidJson() throws Exception {
        String json = """
                {
                  "name": "json-workflow",
                  "description": "JSON workflow",
                  "tasks": [
                    {
                      "id": "task1",
                      "name": "Task 1",
                      "command": "ls -la"
                    },
                    {
                      "id": "task2",
                      "name": "Task 2",
                      "command": "pwd",
                      "depends_on": ["task1"]
                    }
                  ]
                }
                """;

        DagDefinition def = dagParser.parseDefinition(json);

        assertThat(def).isNotNull();
        assertThat(def.getName()).isEqualTo("json-workflow");
        assertThat(def.getTasks()).hasSize(2);
    }

    @Test
    void parseDefinition_ShouldThrowException_WhenInvalidYaml() {
        String invalidYaml = "this is not valid yaml: {{{}";

        assertThatThrownBy(() -> dagParser.parseDefinition(invalidYaml))
                .isInstanceOf(Exception.class);
    }

    @Test
    void buildGraph_ShouldCreateSimpleLinearDag() {
        DagDefinition def = new DagDefinition();
        def.setName("linear-dag");
        def.setTasks(Arrays.asList(
                createTask("task1", "Task 1", null),
                createTask("task2", "Task 2", Arrays.asList("task1")),
                createTask("task3", "Task 3", Arrays.asList("task2"))
        ));

        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);

        assertThat(dag.vertexSet()).containsExactlyInAnyOrder("task1", "task2", "task3");
        assertThat(dag.edgeSet()).hasSize(2);
        assertThat(dag.containsEdge("task1", "task2")).isTrue();
        assertThat(dag.containsEdge("task2", "task3")).isTrue();
    }

    @Test
    void buildGraph_ShouldCreateDiamondDag() {
        DagDefinition def = new DagDefinition();
        def.setName("diamond-dag");
        def.setTasks(Arrays.asList(
                createTask("start", "Start", null),
                createTask("left", "Left", Arrays.asList("start")),
                createTask("right", "Right", Arrays.asList("start")),
                createTask("end", "End", Arrays.asList("left", "right"))
        ));

        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);

        assertThat(dag.vertexSet()).hasSize(4);
        assertThat(dag.edgeSet()).hasSize(4);
        assertThat(dag.containsEdge("start", "left")).isTrue();
        assertThat(dag.containsEdge("start", "right")).isTrue();
        assertThat(dag.containsEdge("left", "end")).isTrue();
        assertThat(dag.containsEdge("right", "end")).isTrue();

        assertThat(dag.incomingEdgesOf("start")).isEmpty();
        assertThat(dag.outgoingEdgesOf("end")).isEmpty();
    }

    @Test
    void buildGraph_ShouldCreateComplexDag() {
        DagDefinition def = new DagDefinition();
        def.setName("complex-dag");
        def.setTasks(Arrays.asList(
                createTask("A", "Task A", null),
                createTask("B", "Task B", null),
                createTask("C", "Task C", Arrays.asList("A")),
                createTask("D", "Task D", Arrays.asList("A", "B")),
                createTask("E", "Task E", Arrays.asList("C", "D")),
                createTask("F", "Task F", Arrays.asList("D"))
        ));

        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);

        assertThat(dag.vertexSet()).hasSize(6);
        assertThat(dag.containsEdge("A", "C")).isTrue();
        assertThat(dag.containsEdge("A", "D")).isTrue();
        assertThat(dag.containsEdge("B", "D")).isTrue();
        assertThat(dag.containsEdge("C", "E")).isTrue();
        assertThat(dag.containsEdge("D", "E")).isTrue();
        assertThat(dag.containsEdge("D", "F")).isTrue();
    }

    @Test
    void buildGraph_ShouldThrowException_WhenDefinitionIsNull() {
        assertThatThrownBy(() -> dagParser.buildGraph(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DagDefinition is null");
    }

    @Test
    void buildGraph_ShouldThrowException_WhenTasksIsNull() {
        DagDefinition def = new DagDefinition();
        def.setName("no-tasks");
        def.setTasks(null);

        assertThatThrownBy(() -> dagParser.buildGraph(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No tasks defined");
    }

    @Test
    void buildGraph_ShouldThrowException_WhenTaskHasNoId() {
        DagDefinition def = new DagDefinition();
        TaskDef task = new TaskDef();
        task.setName("Task without ID");
        task.setCommand("echo test");
        def.setTasks(Arrays.asList(task));

        assertThatThrownBy(() -> dagParser.buildGraph(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty id");
    }

    @Test
    void buildGraph_ShouldThrowException_WhenTaskIdIsBlank() {
        DagDefinition def = new DagDefinition();
        def.setTasks(Arrays.asList(
                createTask("", "Empty ID Task", null)
        ));

        assertThatThrownBy(() -> dagParser.buildGraph(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty id");
    }

    @Test
    void buildGraph_ShouldThrowException_WhenDuplicateTaskIds() {
        DagDefinition def = new DagDefinition();
        def.setTasks(Arrays.asList(
                createTask("task1", "First Task", null),
                createTask("task1", "Duplicate Task", null)
        ));

        assertThatThrownBy(() -> dagParser.buildGraph(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate task id: task1");
    }

    @Test
    void buildGraph_ShouldThrowException_WhenDependencyNotFound() {
        DagDefinition def = new DagDefinition();
        def.setTasks(Arrays.asList(
                createTask("task1", "Task 1", null),
                createTask("task2", "Task 2", Arrays.asList("non-existent-task"))
        ));

        assertThatThrownBy(() -> dagParser.buildGraph(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depends on unknown task");
    }

    @Test
    void buildGraph_ShouldThrowException_WhenCycleDetected() {
        DagDefinition def = new DagDefinition();
        def.setTasks(Arrays.asList(
                createTask("A", "Task A", Arrays.asList("C")),
                createTask("B", "Task B", Arrays.asList("A")),
                createTask("C", "Task C", Arrays.asList("B"))
        ));

        assertThatThrownBy(() -> dagParser.buildGraph(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    void buildGraph_ShouldThrowException_WhenSelfDependency() {
        DagDefinition def = new DagDefinition();
        def.setTasks(Arrays.asList(
                createTask("task1", "Self-dependent Task", Arrays.asList("task1"))
        ));

        assertThatThrownBy(() -> dagParser.buildGraph(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    void topologicalOrder_ShouldReturnCorrectOrderForLinearDag() {
        DagDefinition def = new DagDefinition();
        def.setTasks(Arrays.asList(
                createTask("task1", "Task 1", null),
                createTask("task2", "Task 2", Arrays.asList("task1")),
                createTask("task3", "Task 3", Arrays.asList("task2"))
        ));
        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);

        List<String> order = dagParser.topologicalOrder(dag);

        assertThat(order).containsExactly("task1", "task2", "task3");
    }

    @Test
    void topologicalOrder_ShouldReturnValidOrderForDiamondDag() {
        DagDefinition def = new DagDefinition();
        def.setTasks(Arrays.asList(
                createTask("start", "Start", null),
                createTask("left", "Left", Arrays.asList("start")),
                createTask("right", "Right", Arrays.asList("start")),
                createTask("end", "End", Arrays.asList("left", "right"))
        ));
        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);

        List<String> order = dagParser.topologicalOrder(dag);

        assertThat(order).hasSize(4);
        assertThat(order.indexOf("start")).isLessThan(order.indexOf("left"));
        assertThat(order.indexOf("start")).isLessThan(order.indexOf("right"));
        assertThat(order.indexOf("left")).isLessThan(order.indexOf("end"));
        assertThat(order.indexOf("right")).isLessThan(order.indexOf("end"));
    }

    @Test
    void topologicalOrder_ShouldHandleMultipleRootTasks() {
        DagDefinition def = new DagDefinition();
        def.setTasks(Arrays.asList(
                createTask("root1", "Root 1", null),
                createTask("root2", "Root 2", null),
                createTask("child", "Child", Arrays.asList("root1", "root2"))
        ));
        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);

        List<String> order = dagParser.topologicalOrder(dag);

        assertThat(order).hasSize(3);
        assertThat(order.indexOf("root1")).isLessThan(order.indexOf("child"));
        assertThat(order.indexOf("root2")).isLessThan(order.indexOf("child"));
    }

    @Test
    void parseAndBuildGraph_ShouldWorkEndToEnd() throws Exception {
        String yaml = """
                name: etl-workflow
                description: ETL workflow
                tasks:
                  - id: extract
                    name: Extract Data
                    command: python extract.py
                  - id: transform
                    name: Transform Data
                    command: python transform.py
                    depends_on:
                      - extract
                  - id: load
                    name: Load Data
                    command: python load.py
                    depends_on:
                      - transform
                """;

        DagDefinition def = dagParser.parseDefinition(yaml);
        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);
        List<String> order = dagParser.topologicalOrder(dag);

        assertThat(def.getName()).isEqualTo("etl-workflow");
        assertThat(dag.vertexSet()).hasSize(3);
        assertThat(order).containsExactly("extract", "transform", "load");
    }

    private TaskDef createTask(String id, String name, List<String> dependencies) {
        TaskDef task = new TaskDef();
        task.setId(id);
        task.setName(name);
        task.setCommand("echo " + name);
        task.setDepends_on(dependencies);
        return task;
    }
}
