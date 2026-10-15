package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.jdbctest.config.Config;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Platform checkout behavior is infrastructure validation, not driver compatibility evidence. */
class ScopedDataSourceTest {

  @Test
  void appliesScopeOnEveryCheckoutAndForwardsDifferentCredentials() throws Exception {
    Config config = new Config();
    config.db = new Config.DbConfig();
    config.db.username = "same";
    config.db.password = "literal-${PASSWORD}";
    config.namespace = new Config.NamespaceConfig();
    config.namespace.name = "owned_scope";
    config.namespace.selection = "schema";
    int[] checkouts = {0};
    String[] credentials = new String[2];
    var delegate = new VendorDataSourcesTest.Fixture() {
      @Override
      public Connection getConnection() {
        checkouts[0]++;
        String[] schema = {"public"};
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {Connection.class}, (p, m, a) -> switch (m.getName()) {
              case "setSchema" -> { schema[0] = (String) a[0]; yield null; }
              case "getSchema" -> schema[0];
              case "getAutoCommit" -> true;
              case "close", "setAutoCommit", "rollback" -> null;
              default -> null;
            });
      }
      @Override
      public Connection getConnection(String user, String password) {
        credentials[0] = user;
        credentials[1] = password;
        return getConnection();
      }
    };
    try (var source = new ScopedDataSource(delegate, config)) {
      try (Connection first = source.getConnection()) {
        first.setAutoCommit(false);
        first.rollback();
        assertEquals("owned_scope", first.getSchema());
      }
      try (Connection next = source.getConnection("same", config.db.password)) {
        assertTrue(next.getAutoCommit());
        assertEquals("owned_scope", next.getSchema());
      }
      assertNull(credentials[0]);
      try (Connection other = source.getConnection("other", "other-secret")) {
        assertEquals("owned_scope", other.getSchema());
      }
      assertArrayEquals(new String[] {"other", "other-secret"}, credentials);
      assertEquals(3, checkouts[0]);
    }
  }

  @Test
  void closesConnectionWhenScopeCannotBeApplied() {
    boolean[] closed = {false};
    Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {Connection.class}, (p, m, a) -> {
          if (m.getName().equals("setCatalog")) throw new SQLException("scope denied");
          if (m.getName().equals("close")) closed[0] = true;
          return null;
        });
    Config.NamespaceConfig namespace = new Config.NamespaceConfig();
    namespace.name = "owned_scope";
    namespace.selection = "catalog";
    assertThrows(SQLException.class, () -> ScopedDataSource.scope(connection, namespace));
    assertTrue(closed[0]);
  }
}
