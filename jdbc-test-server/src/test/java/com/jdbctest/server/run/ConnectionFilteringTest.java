package com.jdbctest.server.run;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jdbctest.server.driver.DriverSourceType;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.driver.WorkerProcessRunner;
import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Real H2 queries and HTTP pagination; no target database is contacted.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:connection-filters;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Transactional
class ConnectionFilteringTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jdbc-test.storage.data-directory", () -> directory.toString());
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    DatabaseConnectionProfileRepository connections;
    @Autowired
    JdbcDriverPackageRepository drivers;
    @MockitoBean
    TestRunScheduler scheduler;
    @MockitoBean
    WorkerProcessRunner worker;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var driver = drivers.save(new JdbcDriverPackageEntity("fixture", "arbitrary vendor", "1",
            "fixture.Driver", DriverSourceType.UPLOAD));
        for (int index = 0; index < 7; index++) {
            var connection = new DatabaseConnectionProfileEntity("target-" + index,
                "Historical label",
                index % 2 == 0 ? "postgresql" : "mysql", "jdbc:fixture:never-contacted", "fixture");
            connection.setDefaultDriver(driver);
            connections.save(connection);
        }
    }

    @Test
    void adapterPredicateAppliesBeforeBothPaginationAndCount() throws Exception {
        mvc.perform(get("/api/connections").param("adapterId", "postgresql")
                .param("sort", "name,asc").param("size", "3"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].name").value("target-0"))
            .andExpect(jsonPath("$.items[2].name").value("target-4"));
        mvc.perform(get("/api/connections").param("adapterId", "postgresql")
                .param("sort", "name,asc").param("size", "3").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("target-6"))
            .andExpect(jsonPath("$.items[0].databaseType").value("Historical label"));
        mvc.perform(get("/api/connections").param("adapterId", "missing"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/connections"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(7));
        verifyNoInteractions(worker);
    }

    @Test
    void invalidPaginationAndSortKeepTheirFailureContract() throws Exception {
        for (String query : List.of("size=0", "page=-1", "sort=unknown,asc", "sort=name,invalid",
            "adapterId=" + "x".repeat(101))) {
            mvc.perform(get("/api/connections?" + query)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void catalogHasAdditiveHintsWithoutProbingOrChangingLegacyFields() throws Exception {
        mvc.perform(get("/api/adapters"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7))
            .andExpect(jsonPath("$[3].id").value("postgresql"))
            .andExpect(jsonPath("$[3].status").value("OFFICIAL"))
            .andExpect(jsonPath("$[3].namespaceSelection").value("schema"))
            .andExpect(jsonPath("$[3].driverClass").value("org.postgresql.Driver"))
            .andExpect(jsonPath("$[3].validatedCombinations[0].databaseVersion").value("15.2"))
            .andExpect(jsonPath("$[3].declaration").doesNotExist())
            .andExpect(jsonPath("$[5].id").value("kingbase"))
            .andExpect(jsonPath("$[5].status").value("OFFICIAL"))
            .andExpect(jsonPath("$[5].driverClass").value("com.kingbase8.Driver"))
            .andExpect(jsonPath("$[5].recommendedMavenCoordinate").isEmpty())
            .andExpect(jsonPath("$[5].validatedCombinations.length()").value(1))
            .andExpect(jsonPath("$[5].validatedCombinations[0].databaseVersion").value("12.1"))
            .andExpect(jsonPath("$[5].validatedCombinations[0].driverVersion")
                .value("V008R006B0001"))
            .andExpect(jsonPath("$[5].qualificationNoteKey").value("adapter.kingbaseQualification"))
            .andExpect(jsonPath("$[6].id").value("dm"))
            .andExpect(jsonPath("$[6].dialect").value("dm"))
            .andExpect(jsonPath("$[6].requiresDatabase").value(false))
            .andExpect(jsonPath("$[6].namespaceSelection").value("schema"));
        verifyNoInteractions(worker);
    }
}
