package com.community.cabinet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CabinetServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CabinetServiceApplication.class, args);
    }
}
