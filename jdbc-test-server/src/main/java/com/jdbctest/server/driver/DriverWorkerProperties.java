package com.jdbctest.server.driver;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jdbc-test.worker")
public class DriverWorkerProperties {

    private int maxConcurrentShortWorkers = 4;
    private int maxPendingShortWorkers = 8;
    private Duration shortWorkerWaitTimeout = Duration.ofSeconds(1);

    public int getMaxConcurrentShortWorkers() {
        return maxConcurrentShortWorkers;
    }

    public void setMaxConcurrentShortWorkers(int value) {
        maxConcurrentShortWorkers = value;
    }

    public int getMaxPendingShortWorkers() {
        return maxPendingShortWorkers;
    }

    public void setMaxPendingShortWorkers(int value) {
        maxPendingShortWorkers = value;
    }

    public Duration getShortWorkerWaitTimeout() {
        return shortWorkerWaitTimeout;
    }

    public void setShortWorkerWaitTimeout(Duration value) {
        shortWorkerWaitTimeout = value;
    }

    private Path executable;
    private Duration driverValidationTimeout = Duration.ofSeconds(30);
    private Duration connectionProbeTimeout = Duration.ofSeconds(30);
    private Duration terminationGracePeriod = Duration.ofSeconds(2);

    public Path getExecutable() {
        return executable;
    }

    public void setExecutable(Path executable) {
        this.executable = executable;
    }

    public Duration getDriverValidationTimeout() {
        return driverValidationTimeout;
    }

    public void setDriverValidationTimeout(Duration driverValidationTimeout) {
        this.driverValidationTimeout = driverValidationTimeout;
    }

    public Duration getConnectionProbeTimeout() {
        return connectionProbeTimeout;
    }

    public void setConnectionProbeTimeout(Duration connectionProbeTimeout) {
        this.connectionProbeTimeout = connectionProbeTimeout;
    }

    public Duration getTerminationGracePeriod() {
        return terminationGracePeriod;
    }

    public void setTerminationGracePeriod(Duration terminationGracePeriod) {
        this.terminationGracePeriod = terminationGracePeriod;
    }
}
