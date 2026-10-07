package com.jdbctest.server.driver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface DriverStorage {
    StagedDriverPackage stage(List<MultipartFile> files) throws IOException;
    List<StoredArtifact> moveToDriver(StagedDriverPackage staged, UUID driverId) throws IOException;
    Path resolve(String storageKey);
    void deleteDriver(UUID driverId) throws IOException;
    void deleteStaging(StagedDriverPackage staged);
    record StagedDriverPackage(Path directory, List<StagedArtifact> artifacts) { }
    record StagedArtifact(Path path, String fileName, long size, String sha256) { }
    record StoredArtifact(UUID id, String storageKey, String fileName, long size, String sha256) { }
}
