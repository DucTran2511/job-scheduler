//package com.api.entity;
//
//import jakarta.persistence.*;
//
//@Entity
//@Table(name = "task_dependencies")
//public class TaskDependencyEntity {
//
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "task_id")
//    private TaskEntity task; // the dependent task
//
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "depends_on_id")
//    private TaskEntity dependsOn;
//}
