package com.jdbctest.server.driver;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jdbc-test.storage")
public class DriverStorageProperties {

    private Path dataDirectory = Path.of("./data");
    private int maxArtifacts = 20;
    private long maxFileSize = 100L * 1024 * 1024;
    private long maxPackageSize = 250L * 1024 * 1024;

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public void setDataDirectory(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public int getMaxArtifacts() {
        return maxArtifacts;
    }

    public void setMaxArtifacts(int maxArtifacts) {
        this.maxArtifacts = maxArtifacts;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public long getMaxPackageSize() {
        return maxPackageSize;
    }

    public void setMaxPackageSize(long maxPackageSize) {
        this.maxPackageSize = maxPackageSize;
    }
}
