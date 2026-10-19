package com.jdbctest.catalog;

import java.util.List;
import java.util.Objects;

/**
 * Stable metadata for one reportable JDBC scenario.
 */
public record TestCaseDescriptor(
    String scenarioId,
    TestModule module,
    TestProfile profile,
    String contract,
    String sourceClass,
    String sourceMethod,
    List<String> requiredCapabilities,
    TestCategory category,
    List<String> applicableAdapters,
    String jdbcVersion,
    List<String> executionPrerequisites,
    String unsupportedPolicy
) {

    public TestCaseDescriptor(String scenarioId, TestModule module, TestProfile profile,
        String contract, String sourceClass, String sourceMethod, List<String> capabilities) {
        this(scenarioId, module, profile, contract, sourceClass, sourceMethod, capabilities,
            TestCategory.STANDARD_JDBC, List.of(), "4.0", List.of(), "REQUIRED");
    }

    public ScenarioSelection.Entry selectionEntry() {
        return new ScenarioSelection.Entry(scenarioId, module.id(), profile.name(), category,
            applicableAdapters);
    }

    public TestCaseDescriptor {
        requireText(scenarioId, "scenarioId");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(profile, "profile");
        requireText(contract, "contract");
        requireText(sourceClass, "sourceClass");
        requireText(sourceMethod, "sourceMethod");
        requiredCapabilities = List.copyOf(
            Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        Objects.requireNonNull(category, "category");
        applicableAdapters = List.copyOf(applicableAdapters);
        executionPrerequisites = List.copyOf(executionPrerequisites);
        if (category == TestCategory.VENDOR_EXTENSION && applicableAdapters.isEmpty()) {
            throw new IllegalArgumentException("Vendor extensions require explicit adapters");
        }
        if (category == TestCategory.STANDARD_JDBC && !applicableAdapters.isEmpty()) {
            throw new IllegalArgumentException("Standard JDBC applies to every adapter");
        }
        if (!List.of("REQUIRED", "OPTIONAL").contains(unsupportedPolicy)) {
            throw new IllegalArgumentException("Invalid unsupported policy");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
