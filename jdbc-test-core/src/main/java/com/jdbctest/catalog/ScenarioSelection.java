package com.jdbctest.catalog;

import java.util.List;
import java.util.Set;

/** Shared selection boundary for the source catalog, Server and isolated Worker. */
public final class ScenarioSelection {

    private ScenarioSelection() {
    }

    public record Entry(String id, String moduleId, String profile, TestCategory category,
        List<String> applicableAdapters) {

        public boolean appliesTo(String adapterId) {
            return category == TestCategory.STANDARD_JDBC || category == TestCategory.SQL_FEATURE
                || adapterId != null && applicableAdapters.contains(adapterId);
        }
    }

    public static List<String> resolve(List<Entry> entries, TestSelection selection,
        String adapterId) {
        Set<String> modules = entries.stream().map(Entry::moduleId)
            .collect(java.util.stream.Collectors.toSet());
        if (!modules.containsAll(selection.includedModules())) {
            throw new IllegalArgumentException("Unknown test module");
        }
        Set<String> ids = entries.stream().filter(item -> item.appliesTo(adapterId))
            .map(Entry::id).collect(java.util.stream.Collectors.toSet());
        for (String id : java.util.stream.Stream.concat(selection.includedScenarios().stream(),
            selection.excludedScenarios().stream()).toList()) {
            if (!ids.contains(id)) {
                throw new IllegalArgumentException("Unknown or inapplicable scenario: " + id);
            }
        }
        if (selection.profile() == TestProfile.CUSTOM && selection.includedModules().isEmpty()
            && selection.includedScenarios().isEmpty()) {
            throw new IllegalArgumentException("Custom selection requires a module or scenario");
        }
        return entries.stream().filter(item -> item.appliesTo(adapterId))
            .filter(item -> selection.profile() != TestProfile.CORE || "CORE".equals(item.profile()))
            .filter(item -> selection.includedModules().isEmpty()
                || selection.includedModules().contains(item.moduleId()))
            .filter(item -> selection.includedScenarios().isEmpty()
                || selection.includedScenarios().contains(item.id()))
            .filter(item -> !selection.excludedScenarios().contains(item.id()))
            .map(Entry::id).sorted().toList();
    }
}
