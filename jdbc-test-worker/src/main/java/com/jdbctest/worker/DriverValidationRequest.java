package com.jdbctest.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record DriverValidationRequest(String driverClass, List<String> artifacts) {

}
