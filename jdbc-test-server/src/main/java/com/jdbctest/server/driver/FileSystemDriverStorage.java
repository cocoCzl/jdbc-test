package com.jdbctest.server.driver;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
final class FileSystemDriverStorage implements DriverStorage {

    private final DriverStorageProperties properties;

    FileSystemDriverStorage(DriverStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public StagedDriverPackage stage(List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw invalid("At least one JAR artifact is required");
        }
        if (files.size() > properties.getMaxArtifacts()) {
            throw invalid("Too many JAR artifacts", "DRIVER_ARTIFACT_TOO_LARGE");
        }
        Path directory = root().resolve("staging").resolve(UUID.randomUUID().toString());
        createPrivateDirectory(directory);
        long total = 0;
        try {
            java.util.ArrayList<StagedArtifact> artifacts = new java.util.ArrayList<>();
            for (int index = 0; index < files.size(); index++) {
                MultipartFile file = files.get(index);
                if (file == null) {
                    throw invalid("Artifact is required");
                }
                String fileName = displayName(file.getOriginalFilename());
                if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                    throw invalid("Artifact must use the .jar extension");
                }
                if (file.getSize() > properties.getMaxFileSize()) {
                    throw invalid("Artifact exceeds maximum size", "DRIVER_ARTIFACT_TOO_LARGE");
                }
                Path staged = directory.resolve(index + ".upload");
                CopyResult copied = copyAndDigest(file.getInputStream(), staged,
                    properties.getMaxFileSize(), properties.getMaxPackageSize() - total);
                total += copied.size();
                verifyJar(staged);
                artifacts.add(new StagedArtifact(staged, fileName, copied.size(), copied.sha256()));
            }
            return new StagedDriverPackage(directory, List.copyOf(artifacts));
        } catch (Exception exception) {
            deleteTree(directory);
            if (exception instanceof IOException io) {
                throw io;
            }
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Unable to stage driver artifacts", exception);
        }
    }

    @Override
    public List<StoredArtifact> moveToDriver(StagedDriverPackage staged, UUID driverId)
        throws IOException {
        Path driverDirectory = root().resolve("drivers").resolve(driverId.toString());
        createPrivateDirectory(driverDirectory);
        try {
            java.util.ArrayList<StoredArtifact> stored = new java.util.ArrayList<>();
            for (StagedArtifact artifact : staged.artifacts()) {
                UUID artifactId = UUID.randomUUID();
                Path target = driverDirectory.resolve(artifactId + ".jar");
                move(artifact.path(), target);
                stored.add(new StoredArtifact(artifactId,
                    "drivers/" + driverId + "/" + artifactId + ".jar",
                    artifact.fileName(), artifact.size(), artifact.sha256()));
            }
            return List.copyOf(stored);
        } catch (Exception exception) {
            deleteTree(driverDirectory);
            if (exception instanceof IOException io) {
                throw io;
            }
            throw new IOException("Unable to store driver artifacts", exception);
        }
    }

    @Override
    public Path resolve(String storageKey) {
        Path path = root().resolve(storageKey).normalize();
        if (!path.startsWith(root())) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }

    @Override
    public void deleteDriver(UUID driverId) throws IOException {
        deleteTree(root().resolve("drivers").resolve(driverId.toString()));
    }

    @Override
    public void deleteStaging(StagedDriverPackage staged) {
        deleteTree(staged.directory());
    }

    private Path root() {
        return properties.getDataDirectory().toAbsolutePath().normalize();
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static CopyResult copyAndDigest(InputStream input, Path target, long maxFileSize,
        long packageRemaining) throws IOException {
        if (packageRemaining < 0) {
            throw invalid("Driver package exceeds maximum size", "DRIVER_ARTIFACT_TOO_LARGE");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        long size = 0;
        try (InputStream source = new BufferedInputStream(input);
            var output = Files.newOutputStream(
                target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                size += read;
                if (size > maxFileSize) {
                    throw invalid("Artifact exceeds maximum size", "DRIVER_ARTIFACT_TOO_LARGE");
                }
                if (size > packageRemaining) {
                    throw invalid("Driver package exceeds maximum size",
                        "DRIVER_ARTIFACT_TOO_LARGE");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        return new CopyResult(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static void verifyJar(Path path) throws IOException {
        try (JarFile ignored = new JarFile(path.toFile(), false)) {
            // Opening the archive validates its structure; closing it releases the file handle.
        } catch (IOException exception) {
            throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_ARTIFACT_INVALID",
                "Artifact is not a valid JAR");
        }
    }

    private static String displayName(String original) {
        if (original == null || original.isBlank()) {
            return "artifact.jar";
        }
        String value = original.trim();
        if (value.length() > 255) {
            throw invalid("Artifact file name is too long");
        }
        return value;
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static DriverException invalid(String message) {
        return invalid(message, "DRIVER_ARTIFACT_INVALID");
    }

    private static DriverException invalid(String message, String code) {
        return new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    private record CopyResult(long size, String sha256) {

    }
}
