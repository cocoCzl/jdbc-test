package com.jdbctest.server.driver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@EnabledIf("dockerAvailable")
@SpringBootTest
class DriverRestIT {

  static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Path DATA = createDataDirectory();

  static {
    try {
      POSTGRES.start();
    } catch (RuntimeException exception) {
      POSTGRES = null;
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("jdbc-test.security.master-key",
        () -> Base64.getEncoder().encodeToString(new byte[32]));
    registry.add("jdbc-test.storage.data-directory", () -> DATA.toString());
  }

  static boolean dockerAvailable() {
    return POSTGRES != null;
  }

  @Autowired
  DriverController controller;
  @Autowired
  JdbcDriverPackageRepository drivers;
  @Autowired
  DatabaseConnectionProfileRepository connections;

  @Test
  void driverRestResourceSupportsCreateReadListPatchAndDelete() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new RestErrorHandler())
        .setMessageConverters(new JacksonJsonHttpMessageConverter()).build();
    String metadata = "{\"name\":\" Acme \",\"vendor\":\" Acme \",\"version\":\"1\",\"urlPrefix\":\"jdbc:acme:\"}";
    String body = mvc.perform(multipart("/api/drivers").part(
                new MockPart("metadata", null, metadata.getBytes(StandardCharsets.UTF_8),
                    MediaType.APPLICATION_JSON))
            .file(new MockMultipartFile("files", "../../acme.jar", "application/java-archive", jar())))
        .andExpect(status().isCreated()).andExpect(header().string("Location",
            org.hamcrest.Matchers.matchesPattern("/api/drivers/[0-9a-f-]+")))
        .andExpect(jsonPath("$.driverClass").value("com.acme.Driver")).andReturn().getResponse()
        .getContentAsString();
    String id = new ObjectMapper().readTree(body).path("id").asString();
    mvc.perform(get("/api/drivers/{id}", id)).andExpect(status().isOk())
        .andExpect(jsonPath("$.artifacts[0].fileName").value("../../acme.jar"));
    mvc.perform(get("/api/drivers?page=0&size=20&sort=createdAt,desc")).andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    mvc.perform(patch("/api/drivers/{id}", id).contentType(MediaType.APPLICATION_JSON).content(
            "{\"name\":\"Acme 2\",\"vendor\":\"Acme\",\"version\":\"2\",\"driverClass\":\"com.acme.Fixed\",\"urlPrefix\":\"jdbc:acme:\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_VALIDATION"));
    mvc.perform(delete("/api/drivers/{id}", id)).andExpect(status().isNoContent());
    mvc.perform(get("/api/drivers/{id}", UUID.randomUUID())).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DRIVER_NOT_FOUND"));
  }

  @Test
  void deleteReferencedDriverReturnsConflict() throws Exception {
    JdbcDriverPackageEntity driver = drivers.save(
        new JdbcDriverPackageEntity("used", "Acme", "1", "com.acme.Driver",
            DriverSourceType.UPLOAD));
    DatabaseConnectionProfileEntity connection = new DatabaseConnectionProfileEntity("reference",
        "acme", "jdbc:acme:test", "tester");
    connection.setDefaultDriver(driver);
    connections.save(connection);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new RestErrorHandler())
        .setMessageConverters(new JacksonJsonHttpMessageConverter()).build();
    mvc.perform(delete("/api/drivers/{id}", driver.getId())).andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DRIVER_IN_USE"));
  }

  private static byte[] jar() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (JarOutputStream jar = new JarOutputStream(bytes)) {
      jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
      jar.write("com.acme.Driver\n".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static Path createDataDirectory() {
    try {
      return Files.createTempDirectory("jdbc-driver-rest-");
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
