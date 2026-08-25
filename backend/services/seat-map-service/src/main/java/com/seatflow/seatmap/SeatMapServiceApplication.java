package com.seatflow.seatmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.seatflow.seatmap", "com.seatflow.common"})
@EnableDiscoveryClient
@EnableScheduling
public class SeatMapServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeatMapServiceApplication.class, args);
    }
}
