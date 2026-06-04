package com.jdbctest.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class PropertyGenerator {

    public static void main(String[] args) throws Exception {
        String configPath = System.getProperty("config.yaml");
        if (configPath == null || configPath.isBlank()) {
            configPath = System.getenv("CONFIG_PATH");
        }
        if (configPath == null || configPath.isBlank()) {
            Path localConfig = Path.of(System.getProperty("user.dir", "."), "configs", "config.yaml");
            configPath = Files.exists(localConfig) ? localConfig.toString() : "config.yaml";
        }
        Path configFile = Path.of(configPath);
        if (!Files.exists(configFile)) {
            configFile = Path.of(System.getProperty("user.dir", "."), configPath);
        }

        boolean parallel = false;
        int threads = 1;
        long testTimeout = 0;

        if (Files.exists(configFile)) {
            try (InputStream in = new FileInputStream(configFile.toFile())) {
                Yaml yaml = new Yaml();
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = yaml.load(in);
                @SuppressWarnings("unchecked")
                Map<String, Object> concurrency = (Map<String, Object>) raw.get("concurrency");
                if (concurrency != null) {
                    parallel = Boolean.TRUE.equals(concurrency.get("enabled"));
                    if (concurrency.get("threads") instanceof Number n) {
                        threads = n.intValue();
                    }
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> testFilter = (Map<String, Object>) raw.get("test_filter");
                if (testFilter != null && testFilter.get("timeout") instanceof Number n) {
                    testTimeout = n.longValue();
                }
            }
        }

        String outputDir = System.getProperty("output.dir", "target/test-classes");
        Path outputFile = Path.of(outputDir);
        Files.createDirectories(outputFile);
        outputFile = outputFile.resolve("junit-platform.properties");

        StringBuilder sb = new StringBuilder();
        sb.append("junit.jupiter.execution.parallel.enabled = ").append(parallel).append("\n");
        sb.append("junit.jupiter.execution.parallel.mode.default = same_thread\n");
        sb.append("junit.jupiter.execution.parallel.mode.classes.default = ")
                .append(parallel ? "concurrent" : "same_thread").append("\n");
        if (parallel && threads > 1) {
            sb.append("junit.jupiter.execution.parallel.config.strategy = fixed\n");
            sb.append("junit.jupiter.execution.parallel.config.fixed.parallelism = ")
                    .append(threads).append("\n");
        }
        if (testTimeout > 0) {
            sb.append("junit.jupiter.execution.timeout.test.method.default = ")
                    .append(testTimeout).append(" ms\n");
        }

        try (FileWriter fw = new FileWriter(outputFile.toFile())) {
            fw.write(sb.toString());
        }

        System.out.println("[生成] junit-platform.properties -> " + outputFile.toAbsolutePath());
        System.out.println("[配置] 并行: " + parallel + ", 线程数: " + threads);
    }
}
