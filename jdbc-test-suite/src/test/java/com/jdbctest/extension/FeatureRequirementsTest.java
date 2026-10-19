package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.catalog.AdapterExecutionConfig;
import java.util.*;
import org.junit.jupiter.api.Test;

class FeatureRequirementsTest {

    @RequiresFeature({"blobs", "clobs"})
    static class Fixture {

        @RequiresFeature({"clobs", "lob_objects"})
        void objects() {
        }

        void ordinary() {
        }
    }

    @Test
    void staticCapabilityAssumptionsDoNotDisableStandardTests() throws Exception {
        var required = JdbcTestExtension.requiredFeatures(Fixture.class,
            Fixture.class.getDeclaredMethod("objects"));
        assertEquals(List.of("blobs", "clobs", "lob_objects"), required);
        Map<String, Boolean> declarations = new HashMap<>(
            AdapterExecutionConfig.load("oracle").capabilities());
        declarations.put("blobs", false);
        try {
            FeatureProfile.load(declarations);
            var outcome = JdbcTestExtension.checkFeatures(required);
            assertFalse(outcome.isDisabled());
            declarations.put("blobs", true);
            FeatureProfile.load(declarations);
            assertFalse(JdbcTestExtension.checkFeatures(required).isDisabled());
        } finally {
            FeatureProfile.load(AdapterExecutionConfig.load("postgresql").capabilities());
        }
    }

    @Test
    void missingProvidersArePrerequisitesRatherThanUnsupportedFeatures() {
        try {
            FeatureProfile.load(com.jdbctest.catalog.DataSourceProviders.empty().capabilities(
                AdapterExecutionConfig.load("oracle").capabilities()));
            for (String feature : List.of("data_source", "connection_pool", "xa_data_source")) {
                var outcome = JdbcTestExtension.checkFeatures(List.of(feature));
                assertTrue(outcome.isDisabled());
                assertTrue(outcome.getReason().orElseThrow().startsWith("PREREQUISITE_MISSING:"));
            }
        } finally {
            FeatureProfile.load(AdapterExecutionConfig.load("postgresql").capabilities());
        }
    }

    @Test
    void classRequirementsApplyToMethodsAndContainers() throws Exception {
        assertEquals(List.of("blobs", "clobs"),
            JdbcTestExtension.requiredFeatures(Fixture.class,
                Fixture.class.getDeclaredMethod("ordinary")));
        assertEquals(List.of("blobs", "clobs"),
            JdbcTestExtension.requiredFeatures(Fixture.class, null));
    }
}
