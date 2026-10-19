package com.jdbctest.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScenarioSelectionTest {

    private final List<ScenarioSelection.Entry> entries = List.of(
        new ScenarioSelection.Entry("connection.one", "connection", "CORE",
            TestCategory.STANDARD_JDBC, List.of()),
        new ScenarioSelection.Entry("statement.two", "statement", "FULL",
            TestCategory.STANDARD_JDBC, List.of()),
        new ScenarioSelection.Entry("statement.vendor", "statement", "FULL",
            TestCategory.VENDOR_EXTENSION, List.of("oracle")));

    @Test
    void fullIncludesAllStandardsAndOnlyExplicitlyApplicableExtensions() {
        assertEquals(List.of("connection.one", "statement.two"),
            ScenarioSelection.resolve(entries, TestSelection.full(), "kingbase"));
        assertEquals(List.of("connection.one", "statement.two", "statement.vendor"),
            ScenarioSelection.resolve(entries, TestSelection.full(), "oracle"));
        assertEquals(List.of("connection.one"),
            ScenarioSelection.resolve(entries, TestSelection.core(), "oracle"));
    }

    @Test
    void moduleAndIncludesIntersectAndExclusionWins() {
        var selection = new TestSelection(TestProfile.CUSTOM, Set.of("statement"),
            Set.of("connection.one", "statement.two", "statement.vendor"),
            Set.of("statement.vendor"));
        assertEquals(List.of("statement.two"),
            ScenarioSelection.resolve(entries, selection, "oracle"));
    }

    @Test
    void emptyCustomAndUnknownOrInapplicableIdsAreRejected() {
        for (Set<String> include : List.of(Set.<String>of(), Set.of("deleted.case"),
            Set.of("statement.vendor"))) {
            assertThrows(IllegalArgumentException.class, () -> ScenarioSelection.resolve(entries,
                new TestSelection(TestProfile.CUSTOM, Set.of(), include, Set.of()), "mysql"));
        }
        assertThrows(IllegalArgumentException.class, () -> ScenarioSelection.resolve(entries,
            new TestSelection(TestProfile.FULL, Set.of(), Set.of(), Set.of("deleted.case")), "mysql"));
    }
}
