package com.jdbctest.catalog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCatalogTest {

    private static final Path PROJECT_ROOT = projectRoot();
    private static final TestCatalog CATALOG = TestCatalog.fromScenarioInventory(
            PROJECT_ROOT.resolve("compatibility/v1/scenario-inventory.yaml"),
            PROJECT_ROOT.resolve("jdbc-test-suite/src/main/java")
    );

    private static Path projectRoot() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("compatibility"))
                ? workingDirectory
                : workingDirectory.getParent();
    }

    @Test
    void discoversExpectedScenarioInventory() {
        assertEquals(202, CATALOG.testCases().size());
        assertEquals(17, CATALOG.modules().size());
    }

    @Test
    void scenarioIdsAreUnique() {
        assertEquals(CATALOG.testCases().size(), CATALOG.testCases().stream()
                .map(TestCaseDescriptor::scenarioId).distinct().count());
    }

    @Test
    void moduleIdsAreUnique() {
        assertEquals(CATALOG.modules().size(), CATALOG.modules().stream()
                .map(TestModule::id).distinct().count());
    }

    @Test
    void coreProfileSelectsCurrentCoreScenarios() {
        assertEquals(136, CATALOG.select(TestSelection.core()).size());
        assertTrue(CATALOG.select(TestSelection.core()).stream()
                .allMatch(item -> item.profile() == TestProfile.CORE));
    }

    @Test
    void fullProfileSelectsAllScenarios() {
        assertEquals(202, CATALOG.select(TestSelection.full()).size());
    }

    @Test
    void selectsOneModule() {
        List<TestCaseDescriptor> selected = CATALOG.select(TestSelection.custom(
                TestProfile.FULL, Set.of("statement"), Set.of(), Set.of()));
        assertEquals(23, selected.size());
        assertTrue(selected.stream().allMatch(item -> "statement".equals(item.module().id())));
    }

    @Test
    void selectsMultipleModules() {
        List<TestCaseDescriptor> selected = CATALOG.select(TestSelection.custom(
                TestProfile.FULL, Set.of("connection", "statement"), Set.of(), Set.of()));
        assertEquals(40, selected.size());
    }

    @Test
    void selectsIncludedScenario() {
        List<TestCaseDescriptor> selected = CATALOG.select(TestSelection.custom(
                TestProfile.FULL, Set.of(), Set.of("statement.execute_select"), Set.of()));
        assertEquals(List.of("statement.execute_select"), selected.stream()
                .map(TestCaseDescriptor::scenarioId).toList());
    }

    @Test
    void excludesScenario() {
        List<TestCaseDescriptor> selected = CATALOG.select(TestSelection.custom(
                TestProfile.FULL, Set.of(), Set.of(), Set.of("statement.execute_select")));
        assertEquals(201, selected.size());
        assertTrue(selected.stream().noneMatch(item -> "statement.execute_select".equals(item.scenarioId())));
    }

    @Test
    void exclusionWinsWhenIncludeAndExcludeOverlap() {
        List<TestCaseDescriptor> selected = CATALOG.select(TestSelection.custom(
                TestProfile.FULL, Set.of(), Set.of("statement.execute_select"), Set.of("statement.execute_select")));
        assertTrue(selected.isEmpty());
    }

    @Test
    void rejectsUnknownModule() {
        assertThrows(IllegalArgumentException.class, () -> CATALOG.select(TestSelection.custom(
                TestProfile.FULL, Set.of("not-a-module"), Set.of(), Set.of())));
    }

    @Test
    void rejectsUnknownScenario() {
        assertThrows(IllegalArgumentException.class, () -> CATALOG.select(TestSelection.custom(
                TestProfile.FULL, Set.of(), Set.of("not.a.scenario"), Set.of())));
    }

    @Test
    void integrationTagRemainsOnDatabaseTestClasses() throws Exception {
        Path sourceRoot = PROJECT_ROOT.resolve("jdbc-test-suite/src/main/java");
        long taggedClasses = Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith("Test.java"))
                .filter(path -> {
                    try {
                        String source = Files.readString(path);
                        return source.contains("@ExtendWith(" + "JdbcTestExtension.class)")
                                && source.contains("@org.junit.jupiter.api.Tag(\"integration\")");
                    } catch (IOException exception) {
                        throw new IllegalStateException("Unable to inspect test source: " + path, exception);
                    }
                })
                .count();
        assertEquals(17, taggedClasses);
    }
}
