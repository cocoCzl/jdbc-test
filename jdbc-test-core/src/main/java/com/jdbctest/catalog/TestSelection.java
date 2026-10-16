package com.jdbctest.catalog;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable selection input. The catalog applies profile, module/include filters, then exclusions
 * in that order.
 */
public record TestSelection(
    TestProfile profile,
    Set<String> includedModules,
    Set<String> includedScenarios,
    Set<String> excludedScenarios
) {

    public TestSelection {
        profile = Objects.requireNonNull(profile, "profile");
        includedModules = immutableNames(includedModules, "includedModules");
        includedScenarios = immutableNames(includedScenarios, "includedScenarios");
        excludedScenarios = immutableNames(excludedScenarios, "excludedScenarios");
    }

    public static TestSelection core() {
        return new TestSelection(TestProfile.CORE, Set.of(), Set.of(), Set.of());
    }

    public static TestSelection full() {
        return new TestSelection(TestProfile.FULL, Set.of(), Set.of(), Set.of());
    }

    public static TestSelection custom(
        TestProfile profile,
        Set<String> modules,
        Set<String> includedScenarios,
        Set<String> excludedScenarios
    ) {
        return new TestSelection(profile, modules, includedScenarios, excludedScenarios);
    }

    private static Set<String> immutableNames(Set<String> values, String field) {
        Objects.requireNonNull(values, field);
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must contain non-blank names");
            }
            normalized.add(value);
        }
        return Collections.unmodifiableSet(normalized);
    }
}
