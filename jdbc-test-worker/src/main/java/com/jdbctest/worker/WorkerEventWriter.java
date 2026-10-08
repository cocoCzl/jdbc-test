package com.jdbctest.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorkerEventWriter {

  private final ObjectMapper mapper;
  private final PrintStream output;

  WorkerEventWriter(ObjectMapper mapper) {
    this(mapper, System.out);
  }

  WorkerEventWriter(ObjectMapper mapper, PrintStream output) {
    this.mapper = mapper;
    this.output = output;
  }

  synchronized void write(String type, String runId, Map<String, ?> fields) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", type);
    event.put("runId", runId == null ? "" : runId);
    event.put("timestamp", Instant.now().toString());
    if (fields != null) {
      event.putAll(fields);
    }
    try {
      output.println(mapper.writeValueAsString(event));
      output.flush();
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to write worker event", exception);
    }
  }
}
