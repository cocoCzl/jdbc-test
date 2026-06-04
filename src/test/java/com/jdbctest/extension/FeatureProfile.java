package com.jdbctest.extension;

import com.jdbctest.config.Config;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public class FeatureProfile {

    private static volatile Map<String, Boolean> features = Collections.emptyMap();

    public static void load(Config.DbType dbType, String profileDir) {
        String name = dbType.name().toLowerCase();
        Path path = Path.of(System.getProperty("user.dir", "."), profileDir, name + ".yaml");
        if (!Files.exists(path)) {
            path = Path.of(profileDir, name + ".yaml");
        }

        try (InputStream in = new FileInputStream(path.toFile())) {
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = yaml.load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> featureMap = (Map<String, Object>) raw.get("features");
            if (featureMap != null) {
                Map<String, Boolean> map = new java.util.HashMap<>();
                for (var entry : featureMap.entrySet()) {
                    map.put(entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
                }
                features = Collections.unmodifiableMap(map);
            }
        } catch (Exception e) {
            System.err.println("[WARN] 加载数据库能力配置失败: " + path.toAbsolutePath() + " - " + e.getMessage());
            features = Collections.emptyMap();
        }
    }

    public static boolean supports(String feature) {
        if (!features.containsKey(feature)) {
            System.err.println("[WARN] 未知的能力标记: " + feature + "，已禁用以确保安全");
            return false;
        }
        return features.get(feature);
    }
}
