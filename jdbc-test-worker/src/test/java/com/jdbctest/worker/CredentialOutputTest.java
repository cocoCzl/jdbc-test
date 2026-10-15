package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialOutputTest {

  @Test
  void eventsAndExceptionChainsUseTheSameSafeProjection() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var mapper = new ObjectMapper();
    var fields = WorkerExecutionEvidence.failure(new SQLException(
        "jdbc:oracle:thin:u/AuditOnly_Secret@host", "XX000", 7,
        new IllegalStateException("password={AuditOnly;SecretTail}}End}")));
    fields.put("nested", Map.of("access_token", "TokenSecret"));
    new WorkerEventWriter(mapper, new PrintStream(bytes)).write("EXECUTION_ISSUE", "fixture", fields);
    String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(output.contains("AuditOnly"));
    assertFalse(output.contains("SecretTail"));
    assertFalse(output.contains("TokenSecret"));
    assertEquals(7, mapper.readTree(output).path("vendorCode").asInt());
    assertEquals("TokenSecret", ((Map<?, ?>) fields.get("nested")).get("access_token"));
  }
}
