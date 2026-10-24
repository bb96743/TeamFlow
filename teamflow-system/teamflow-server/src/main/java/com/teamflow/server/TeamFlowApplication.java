package com.teamflow.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@MapperScan({
        "com.teamflow.system.mapper",
        "com.teamflow.generator.mapper"
})
@SpringBootApplication(scanBasePackages = "com.teamflow")
public class TeamFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeamFlowApplication.class, args);
    }
}
