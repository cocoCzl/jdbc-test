package com.jdbctest.catalog;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Read-only metadata for the adapters packaged with the application.
 */
public final class AdapterCatalog {

  private static final List<String> IDS = List.of("gaussdb", "mysql", "oracle", "postgresql",
      "sqlserver");

  private AdapterCatalog() {
  }

  public static List<AdapterMetadata> list() {
    List<AdapterMetadata> adapters = new ArrayList<>();
    for (String id : IDS) {
      adapters.add(load(id));
    }
    return List.copyOf(adapters);
  }

  public static boolean contains(String id) {
    return IDS.contains(id);
  }

  @SuppressWarnings("unchecked")
  private static AdapterMetadata load(String id) {
    String path = "adapters/" + id + "/adapter.json";
    try (InputStream input = AdapterCatalog.class.getClassLoader().getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalStateException("Packaged adapter metadata is missing: " + id);
      }
      Map<String, Object> manifest = new Yaml().load(input);
      String name = String.valueOf(manifest.get("name"));
      String trust = String.valueOf(manifest.get("trust"));
      return new AdapterMetadata(id, name, "official".equals(trust) ? "OFFICIAL" : "CANDIDATE");
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to read packaged adapter metadata: " + id, exception);
    }
  }

  public record AdapterMetadata(String id, String name, String status) {

  }
}
