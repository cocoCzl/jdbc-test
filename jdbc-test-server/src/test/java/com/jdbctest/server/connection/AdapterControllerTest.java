package com.jdbctest.server.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdbctest.catalog.AdapterCatalog;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.json.JsonFactory;

class AdapterControllerTest {
  @Test
  void indexedCustomAdapterReachesApiWithoutProductOrDriverClasspathChanges() throws Exception {
    String manifest;
    try (InputStream input = getClass().getResourceAsStream("/adapters/postgresql/adapter.json")) {
      manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8)
          .replace("\"id\": \"postgresql\"", "\"id\": \"private-db\"")
          .replace("\"assets\": {", "\"assets\": {\"directory\":\"postgresql\",");
    }
    String customManifest = manifest;
    URL[] urls = {
        AdapterCatalog.class.getProtectionDomain().getCodeSource().getLocation(),
        AdapterController.class.getProtectionDomain().getCodeSource().getLocation(),
        JsonFactory.class.getProtectionDomain().getCodeSource().getLocation()
    };
    try (URLClassLoader isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
      @Override
      public InputStream getResourceAsStream(String name) {
        if ("adapters/catalog.json".equals(name)) {
          return new ByteArrayInputStream("[\"private-db\"]".getBytes(StandardCharsets.UTF_8));
        }
        if ("adapters/private-db/adapter.json".equals(name)) {
          return new ByteArrayInputStream(customManifest.getBytes(StandardCharsets.UTF_8));
        }
        return super.getResourceAsStream(name);
      }
    }) {
      Class<?> controller = isolated.loadClass(AdapterController.class.getName());
      var constructor = controller.getDeclaredConstructor();
      constructor.setAccessible(true);
      var list = controller.getDeclaredMethod("list");
      list.setAccessible(true);
      List<?> adapters = (List<?>) list.invoke(constructor.newInstance());
      assertEquals(1, adapters.size());
      assertEquals("private-db", adapters.getFirst().getClass().getMethod("id").invoke(adapters.getFirst()));
      assertThrows(ClassNotFoundException.class, () -> isolated.loadClass("org.postgresql.Driver"));
      assertThrows(ClassNotFoundException.class,
          () -> isolated.loadClass("com.jdbctest.extension.JdbcContext"));
      assertTrue(AdapterCatalog.contains("postgresql"));
    }
  }
}
