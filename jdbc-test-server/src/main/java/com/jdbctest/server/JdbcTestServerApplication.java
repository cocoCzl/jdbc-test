package com.jdbctest.server;

import com.jdbctest.server.driver.DriverStorageProperties;
import com.jdbctest.server.driver.DriverWorkerProperties;
import com.jdbctest.server.run.ExecutionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({DriverStorageProperties.class, DriverWorkerProperties.class,
    ExecutionProperties.class})
public class JdbcTestServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JdbcTestServerApplication.class, args);
    }
}
