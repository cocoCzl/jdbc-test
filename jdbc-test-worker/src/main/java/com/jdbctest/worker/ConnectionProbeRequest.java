package com.jdbctest.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ConnectionProbeRequest(WorkerRunRequest.DriverRequest driver, Connection connection) {

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Connection(String jdbcUrl, String username, String password) {

  }
}
