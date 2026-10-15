package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdbctest.catalog.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.*;

class WorkerExecutionEvidenceTest {

  static class BrokenSetup {

    @BeforeAll
    static void setup() {
      throw new IllegalStateException("fixture setup failure");
    }

    @Test
    void first() {
      fail("must not execute");
    }

    @Test
    void second() {
      fail("must not execute");
    }
  }

  static class Passed {
    @Test
    void first() {
    }

    @Test
    void second() {
    }
  }

  @Disabled("fixture explicit container skip")
  static class SkippedContainer {

    @Test
    void first() {
      fail("must not execute");
    }

    @Test
    void second() {
      fail("must not execute");
    }
  }

  @TestMethodOrder(MethodOrderer.MethodName.class)
  static class Mixed {

    @Test
    void first() {
    }

    @Test
    void second() {
      throw new AssertionError("fixture assertion failure");
    }
  }

  WorkerExecutionEvidence execute(Class<?> fixture) {
    var module = new TestModule("fixture", "Fixture", "Evidence regression");
    var selected = List.of("first", "second").stream().map(
        method -> new TestCaseDescriptor("fixture." + method, module, TestProfile.CORE, method,
            fixture.getName(), method, List.of())).toList();
    var evidence = new WorkerExecutionEvidence("fixture", selected,
        new WorkerEventWriter(new ObjectMapper(), new PrintStream(new ByteArrayOutputStream())));
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectClass(fixture)).build();
    LauncherFactory.create().execute(request, evidence);
    return evidence;
  }

  @Test
  void cleanupChangesOnlyRunConclusionAndPreservesPrimaryFailure() {
    var evidence = execute(Passed.class);
    assertEquals("CLEANUP_FAILURE", evidence.terminationReason());
    evidence.recordCleanup(Map.of("status", "FAILED"));
    assertEquals("CLEANUP_FAILURE", evidence.terminationReason());
    assertEquals(2, evidence.results().stream().filter(r -> "PASSED".equals(r.get("status"))).count());
    evidence.recordCleanup(Map.of("status", "SUCCESS"));
    assertEquals("COMPLETED", evidence.terminationReason());
    var failed = execute(Mixed.class);
    failed.recordCleanup(Map.of("status", "FAILED"));
    assertEquals("TEST_FAILURE", failed.terminationReason());
  }

  @Test
  void wrappedSqlFailurePreservesOriginalCauseAndVendorEvidence() {
    var evidence = WorkerExecutionEvidence.failure(
        new IllegalStateException("fixture initialization",
            new java.sql.SQLException("fixture SQL failure", "42000", 777)));
    assertEquals("42000", evidence.get("sqlState"));
    assertEquals(777, evidence.get("vendorCode"));
    var causes = (List<Map<String, Object>>) evidence.get("causes");
    assertEquals("fixture SQL failure", causes.get(1).get("message"));
  }

  @Test
  void beforeAllFailureRetainsContainerFailureWithoutInventedChildResults() {
    var evidence = execute(BrokenSetup.class);
    assertTrue(evidence.results().isEmpty());
    assertTrue(evidence.started().isEmpty());
    assertEquals(1, evidence.issues().size());
    assertEquals("CONTAINER_FAILURE", evidence.terminationReason());
    assertEquals("fixture setup failure", evidence.issues().getFirst().get("message"));
  }

  @Test
  void explicitContainerSkipMapsToSelectedChildrenWithSource() {
    var evidence = execute(SkippedContainer.class);
    assertEquals(2, evidence.results().size());
    assertTrue(evidence.started().isEmpty());
    assertTrue(evidence.results().stream()
        .allMatch(row -> row.get("status").equals("SKIPPED") && row.containsKey("skipSource")));
    assertEquals("NO_TESTS_EXECUTED", evidence.terminationReason());
  }

  @Test
  void realFailureIsOneResultAndNotContainerFailure() {
    var evidence = execute(Mixed.class);
    assertEquals(2, evidence.started().size());
    assertTrue(evidence.issues().isEmpty());
    assertEquals(List.of("PASSED", "FAILED"),
        evidence.results().stream().map(row -> row.get("status")).toList());
    assertEquals("TEST_FAILURE", evidence.terminationReason());
  }
}
