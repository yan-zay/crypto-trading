package com.tj.crypto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Author: zay
 * @Date: 2024-02-29 10:11
 */

@SpringBootApplication
@ComponentScan(
        basePackages = {
                "com.tj.crypto.**",
        }
)
@EnableAsync
@EnableScheduling
public class CryptoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoApplication.class, args);
    }
}
