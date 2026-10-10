package com.jdbctest.server.run;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jdbc-test.execution")
public class ExecutionProperties {

  private int maxConcurrentRuns = 1;
  private int queueCapacity = 64;
  private Duration runTimeout = Duration.ofMinutes(30);
  private Duration terminationGracePeriod = Duration.ofSeconds(2);

  public int getMaxConcurrentRuns() {
    return maxConcurrentRuns;
  }

  public void setMaxConcurrentRuns(int value) {
    this.maxConcurrentRuns = value;
  }

  public int getQueueCapacity() {
    return queueCapacity;
  }

  public void setQueueCapacity(int value) {
    this.queueCapacity = value;
  }

  public Duration getRunTimeout() {
    return runTimeout;
  }

  public void setRunTimeout(Duration value) {
    this.runTimeout = value;
  }

  public Duration getTerminationGracePeriod() {
    return terminationGracePeriod;
  }

  public void setTerminationGracePeriod(Duration value) {
    this.terminationGracePeriod = value;
  }
}
