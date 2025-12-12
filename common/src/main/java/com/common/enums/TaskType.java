package com.common.enums;

public enum TaskType {
    SHELL,
    HTTP,
    PYTHON,
    DOCKER;

    public static TaskType fromString(String type) {
        if (type == null || type.trim().isEmpty()) {
            return SHELL;
        }
        try {
            return TaskType.valueOf(type.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return SHELL;
        }
    }
}
