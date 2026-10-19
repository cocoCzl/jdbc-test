package com.jdbctest.extension;

import java.util.Collections;
import java.util.Map;

/** Legacy adapter declarations; a true value does not establish observed JDBC support. */
public class FeatureProfile {

    private static volatile Map<String, Boolean> features = Collections.emptyMap();

    public static void load(Map<String, Boolean> declarations) {
        features = com.jdbctest.catalog.AdapterExecutionConfig.validateCapabilities(declarations);
    }

    public static boolean supports(String feature) {
        if (!features.containsKey(feature)) {
            throw new IllegalStateException("ADAPTER_CAPABILITY_MISSING: " + feature);
        }
        return features.get(feature);
    }
}
