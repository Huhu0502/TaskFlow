package com.taskflow.center;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.taskflow.dao.mapper")
public class TaskFlowCenterApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaskFlowCenterApplication.class, args);
    }
}
