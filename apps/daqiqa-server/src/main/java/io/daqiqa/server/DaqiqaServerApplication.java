package io.daqiqa.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.daqiqa")
@EnableScheduling
public class DaqiqaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DaqiqaServerApplication.class, args);
    }
}
