package com.jdbctest.extension;

import com.jdbctest.config.Config;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.XADataSource;

/** Loads the configured provider from the same isolated artifacts as the tested driver. */
public final class VendorDataSources {

  private VendorDataSources() {
  }

  public static javax.sql.DataSource dataSource(Config.DbConfig config) throws Exception {
    return configure(config, config.dataSources.dataSource(), javax.sql.DataSource.class);
  }

  public static XADataSource xa(Config.DbConfig config) throws Exception {
    return configure(config, config.dataSources.xaDataSource(), XADataSource.class);
  }

  public static ConnectionPoolDataSource pooled(Config.DbConfig config) throws Exception {
    return configure(config, config.dataSources.connectionPoolDataSource(),
        ConnectionPoolDataSource.class);
  }

  private static <T> T configure(Config.DbConfig config,
      com.jdbctest.catalog.DataSourceProviders.Provider provider, Class<T> contract)
      throws Exception {
    if (provider == null) {
      throw new IllegalStateException("Configure a driver-owned " + contract.getSimpleName()
          + " implementation in db.data_sources");
    }
    Object source = Class.forName(provider.className(), true, JdbcContext.driverClassLoader())
        .getDeclaredConstructor().newInstance();
    if (!contract.isInstance(source)) {
      throw new IllegalStateException("Configured provider must implement " + contract.getName());
    }
    // No vendor-specific APIs: configure public JavaBean properties on the supplied provider.
    for (var entry : config.properties.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(provider.urlProperty())
          || entry.getKey().equalsIgnoreCase(provider.userProperty())
          || entry.getKey().equalsIgnoreCase(provider.passwordProperty())) {
        throw new IllegalStateException("Connection properties cannot override provider credentials");
      }
      set(source, entry.getKey(), entry.getValue());
    }
    set(source, provider.urlProperty(), config.getJdbcUrl());
    set(source, provider.userProperty(), config.username);
    set(source, provider.passwordProperty(), config.password);
    return contract.cast(source);
  }

  private static void set(Object source, String property, String value) throws Exception {
    String setter = "set" + property;
    Method method = java.util.Arrays.stream(source.getClass().getMethods())
        .filter(item -> item.getName().equalsIgnoreCase(setter)
            && item.getParameterCount() == 1)
        .sorted(java.util.Comparator.comparingInt(item ->
            item.getParameterTypes()[0] == String.class ? 0 : 1))
        .findFirst().orElseThrow(() -> new IllegalStateException(
            "DataSource provider cannot apply configured property: " + property));
    Class<?> type = method.getParameterTypes()[0];
    Object argument;
    if (type == String.class) {
      argument = value;
    } else if (type == int.class || type == Integer.class) {
      argument = Integer.valueOf(value);
    } else if (type == boolean.class || type == Boolean.class) {
      if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
        throw new IllegalStateException("DataSource provider property must be boolean: " + property);
      }
      argument = Boolean.valueOf(value);
    } else if (type == long.class || type == Long.class) {
      argument = Long.valueOf(value);
    } else {
      throw new IllegalStateException("Unsupported DataSource provider property type: " + property);
    }
    invoke(source, method, argument);
  }

  private static void invoke(Object source, Method method, Object... arguments) throws Exception {
    try {
      method.invoke(source, arguments);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof SQLException sql) {
        throw new SQLException("DataSource provider configuration failed", sql.getSQLState(),
            sql.getErrorCode());
      }
      throw new IllegalStateException("DataSource provider configuration failed"); // Avoid leaking credential values.
    }
  }
}
