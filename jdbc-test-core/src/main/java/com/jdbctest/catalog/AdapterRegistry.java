package com.jdbctest.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable registry loaded once per application classloader from an explicit resource index.
 */
public final class AdapterRegistry {
  private final Map<String, AdapterDefinition> definitions;

  private AdapterRegistry(Map<String, AdapterDefinition> definitions) {
    this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
  }

  public static AdapterRegistry bundled() {
    return Bundled.INSTANCE;
  }

  /**
   * A separate loader also permits isolated fixture registries without global mutable state.
   */
  public static AdapterRegistry load(ClassLoader resources) {
    try (InputStream index = resources.getResourceAsStream("adapters/catalog.json")) {
      if (index == null || !(AdapterJson.read(index) instanceof List<?> ids) || ids.isEmpty()) {
        throw new IllegalArgumentException("ADAPTER_INDEX_MISSING_OR_INVALID");
      }
      Map<String, AdapterDefinition> definitions = new LinkedHashMap<>();
      for (Object value : ids) {
        String id = AdapterManifest.id(value);
        if (definitions.containsKey(id)) {
          throw new IllegalArgumentException("ADAPTER_INDEX_DUPLICATE: " + id);
        }
        try (InputStream input = resources.getResourceAsStream("adapters/" + id + "/adapter.json")) {
          if (input == null) {
            throw new IllegalArgumentException("ADAPTER_MANIFEST_MISSING: " + id);
          }
          AdapterDefinition definition = AdapterManifest.read(input);
          if (!id.equals(definition.id())) {
            throw new IllegalArgumentException("ADAPTER_INDEX_ID_MISMATCH: " + id);
          }
          var assets = definition.assets();
          if (!"ddl".equals(assets.ddl()) || !"dml".equals(assets.dml())
              || !"pool".equals(assets.pool())) {
            throw new IllegalArgumentException("ADAPTER_PACKAGED_ASSET_ROOT_INVALID: " + id);
          }
          definitions.put(id, definition);
        }
      }
      return new AdapterRegistry(definitions);
    } catch (IOException exception) {
      throw new IllegalStateException("ADAPTER_RESOURCES_UNREADABLE", exception);
    }
  }

  public List<AdapterDefinition> list() {
    return List.copyOf(definitions.values());
  }

  public boolean contains(String id) {
    return definitions.containsKey(id);
  }

  public AdapterDefinition required(String id) {
    AdapterDefinition definition = definitions.get(id);
    if (definition == null) {
      throw new IllegalArgumentException("Unknown execution adapter");
    }
    return definition;
  }

  private static final class Bundled {
    private static final AdapterRegistry INSTANCE = load(AdapterRegistry.class.getClassLoader());
  }
}
