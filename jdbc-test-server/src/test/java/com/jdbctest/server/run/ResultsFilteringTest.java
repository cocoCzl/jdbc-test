package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jdbctest.server.connection.*;
import com.jdbctest.server.driver.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Real Repository queries and database pagination against isolated H2, not PostgreSQL evidence. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:result-filters;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ResultsFilteringTest {

  @org.junit.jupiter.api.io.TempDir
  static java.nio.file.Path directory;

  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("jdbc-test.storage.data-directory", () -> directory.toString());
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcDriverPackageRepository drivers;
  @Autowired DatabaseConnectionProfileRepository connections;
  @Autowired TestRunRepository runs;
  @Autowired TestCaseResultRepository results;
  @Autowired TestRunService service;
  @MockitoBean TestRunScheduler scheduler;
  MockMvc mvc;
  UUID id;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    var driver = drivers.save(new JdbcDriverPackageEntity("fixture", "fixture", "1", "fixture.Driver", DriverSourceType.UPLOAD));
    var connection = new DatabaseConnectionProfileEntity("fixture", "postgresql", "postgresql", "jdbc:fixture:test", "fixture");
    connection.setDefaultDriver(driver);
    connection = connections.save(connection);
    for (int runIndex = 0; runIndex < 2; runIndex++) {
      var run = runs.save(new TestRunEntity(connection, driver, "CORE", "fixture", "postgresql", "postgresql",
          "jdbc:fixture:test", "fixture", "fixture", "1", "fixture.Driver", null));
      if (runIndex == 0) id = run.getId();
      for (int index = 0; index < 7; index++) {
        String module = index < 4 ? "connection" : "statement";
        var row = new TestCaseResultEntity(run, module + ".fixture_" + index, module, "fixture",
            index == 3 || index == 6 ? TestCaseResultStatus.PASSED : TestCaseResultStatus.FAILED);
        row.complete(42L, null, null);
        results.save(row);
      }
    }
  }

  @Test
  void intersectionAndPageTotalsUseTheSameDatabasePredicate() throws Exception {
    mvc.perform(get("/api/runs/{id}/results", id).param("status", "FAILED")
            .param("moduleId", "connection").param("size", "2").param("sort", "durationMs,desc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].scenarioId").value("connection.fixture_0"))
        .andExpect(jsonPath("$.items[1].scenarioId").value("connection.fixture_1"));
    var last = service.results(id, 1, 2, "durationMs,desc", TestCaseResultStatus.FAILED, "connection");
    assertEquals(3, last.totalElements());
    assertEquals(List.of("connection.fixture_2"), last.items().stream().map(TestCaseResultResponse::scenarioId).toList());
    assertTrue(service.results(id, 2, 2, null, TestCaseResultStatus.FAILED, "connection").items().isEmpty());
    assertEquals(7, service.results(id, 0, 20, null, null, null).totalElements());
    assertEquals(5, service.results(id, 0, 20, null, TestCaseResultStatus.FAILED, null).totalElements());
    assertEquals(3, service.results(id, 0, 20, null, null, "statement").totalElements());
    assertEquals(0, service.results(id, 0, 20, null, TestCaseResultStatus.FAILED, "missing").totalElements());
    assertEquals(7, service.results(id, 0, 20, null, null, "").totalElements());
  }

  @Test
  void invalidParametersRetainProblemContract() throws Exception {
    for (String query : List.of("status=invalid", "size=0", "page=-1", "sort=unknown,asc", "sort=status,invalid")) {
      mvc.perform(get("/api/runs/" + id + "/results?" + query)).andExpect(status().isBadRequest());
    }
  }
}
