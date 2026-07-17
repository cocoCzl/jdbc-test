package com.jdbctest.extension;

import java.util.Collections;
import java.util.Map;

public class FeatureProfile {

    private static volatile Map<String, Boolean> features = Collections.emptyMap();

    public static void load(Map<String, Boolean> declarations) {
        features = declarations == null ? Collections.emptyMap() : Collections.unmodifiableMap(declarations);
    }

    public static boolean supports(String feature) {
        if (!features.containsKey(feature)) {
            System.err.println("[WARN] 未知的能力标记: " + feature + "，已禁用以确保安全");
            return false;
        }
        return features.get(feature);
    }
}
