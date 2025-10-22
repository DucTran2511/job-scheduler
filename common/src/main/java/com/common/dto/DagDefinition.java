package com.common.dto;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DagDefinition {
    private String name;
    private String description;
    private List<TaskDef> tasks;
}
