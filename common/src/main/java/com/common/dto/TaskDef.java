package com.common.dto;


import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TaskDef {
    private String id;
    private String name;
    private String command;
    private String taskType;  // SHELL, HTTP, PYTHON, DOCKER
    private List<String> depends_on;
    private Integer maxRetries;
    private Integer timeoutSeconds;
    private Object params;
}
