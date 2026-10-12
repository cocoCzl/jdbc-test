package com.jdbctest.server.deploy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotenvConfigurationTest {
  @TempDir Path directory;
  private Path file(String content) throws Exception {
    Path file = directory.resolve(".env");
    DeploymentTool.publish(file, content);
    return file;
  }
  @Test void literalPasswordSurvivesKeyUpdateIncludingSpacesAndPlaceholder() throws Exception {
    String password = "  $ # ' \" \\ = ${NAME} 中文é🔑  ";
    Path file = file("SPRING_DATASOURCE_PASSWORD=" + password + "\nJDBC_TEST_MASTER_KEY=\n");
    var config = DotenvConfiguration.read(file);
    assertEquals(password, config.required("SPRING_DATASOURCE_PASSWORD"));
    config.update(Map.of("JDBC_TEST_MASTER_KEY", "test-key"));
    assertEquals(password, DotenvConfiguration.read(file).required("SPRING_DATASOURCE_PASSWORD"));
    assertEquals("test-key", DotenvConfiguration.read(file).required("JDBC_TEST_MASTER_KEY"));
  }
  @Test void malformedOrDuplicateValueIsRejectedWithoutRevealingIt() throws Exception {
    Path file = file("PASSWORD=\"secret\" trailing\n");
    assertFalse(assertThrows(Exception.class, () -> DotenvConfiguration.read(file)).getMessage().contains("secret"));
    Files.writeString(file, "PASSWORD=one\nPASSWORD=two\n");
    assertThrows(Exception.class, () -> DotenvConfiguration.read(file));
    Files.writeString(file, "PASSWORD=line\tbreak\n");
    assertThrows(Exception.class, () -> DotenvConfiguration.read(file));
  }
  @Test void preparedFileCannotOverwriteConcurrentEditOrExistingTemplate() throws Exception {
    Path file = file("SPRING_DATASOURCE_URL=\nSPRING_DATASOURCE_USERNAME=\nSPRING_DATASOURCE_PASSWORD=\nJDBC_TEST_MASTER_KEY=\n");
    var config = DotenvConfiguration.read(file);
    Files.writeString(file, "PASSWORD=edited\n");
    assertThrows(Exception.class, () -> config.update(Map.of("JDBC_TEST_MASTER_KEY", "key")));
    assertEquals("PASSWORD=edited\n", Files.readString(file));
    assertThrows(Exception.class, () -> DeploymentTool.publish(file, "replacement"));
  }
  @Test void copiedExampleIsSecuredWithoutChangingItsContents() throws Exception {
    Path file = directory.resolve(".env");
    Files.writeString(file, "PASSWORD=  literal $ # ${NAME} 中文  \n");
    byte[] before = Files.readAllBytes(file);
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
    DotenvConfiguration.protectLocalFile(file);
    DeploymentTool.assertPrivate(file);
    assertArrayEquals(before, Files.readAllBytes(file));
  }
  @Test void missingBundledPasswordRequiresStateGuardEvenWithAnExistingKey() throws Exception {
    Path file = file("JDBC_TEST_MASTER_KEY=existing-key\nPOSTGRES_PASSWORD=\n");
    assertTrue(DotenvConfiguration.read(file).needsDockerSecrets(true));
    assertFalse(DotenvConfiguration.read(file).needsDockerSecrets(false));
    Files.writeString(file, "JDBC_TEST_MASTER_KEY=existing-key\nPOSTGRES_PASSWORD=existing-password\n");
    assertFalse(DotenvConfiguration.read(file).needsDockerSecrets(true));
  }
  @Test void legacyGeneratedComposeFileIsDecodedOnce() throws Exception {
    String password = "$$ ${NAME} # ' \" \\ 中文";
    Path file = file("# Compose dotenv only; NEVER source/eval\nPASSWORD=" + DeploymentTool.dotenv(password) + "\n");
    assertEquals(password, DotenvConfiguration.read(file).required("PASSWORD"));
  }
  @Test void recoveryWithMissingKeyRefusesLocalDataBeforeConnecting() throws Exception {
    Path file = file("SPRING_DATASOURCE_URL=\nSPRING_DATASOURCE_USERNAME=\nSPRING_DATASOURCE_PASSWORD=\nJDBC_TEST_MASTER_KEY=\n");
    Path data = directory.resolve("data"); Files.createDirectory(data); Files.writeString(data.resolve("saved"), "data");
    byte[] before = Files.readAllBytes(file);
    assertThrows(Exception.class, () -> DotenvConfiguration.read(file).ensureKey(data, true));
    assertArrayEquals(before, Files.readAllBytes(file));
    assertFalse(Files.exists(directory.resolve(".jdbc-test-init.lock")));
  }
}
