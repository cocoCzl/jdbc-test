package com.jdbctest.worker;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.List;

final class DriverLoader {

  private DriverLoader() {
  }

  static LoadedDriver load(WorkerRunRequest.DriverRequest request) {
    if (request == null || request.driverClass() == null || request.driverClass().isBlank()) {
      throw new IllegalArgumentException("driver.driverClass is required");
    }
    List<String> artifacts = request.artifacts() == null ? List.of() : request.artifacts();
    try {
      URL[] urls = new URL[artifacts.size()];
      for (int i = 0; i < artifacts.size(); i++) {
        Path path = Path.of(artifacts.get(i)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
          throw new IllegalArgumentException("Driver artifact does not exist: " + path);
        }
        urls[i] = path.toUri().toURL();
      }
      URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
      Class<?> type = Class.forName(request.driverClass(), true, loader);
      if (!Driver.class.isAssignableFrom(type)) {
        loader.close();
        throw new IllegalArgumentException(
            "Driver class is not java.sql.Driver: " + request.driverClass());
      }
      Driver driver = (Driver) type.getDeclaredConstructor().newInstance();
      return new LoadedDriver(loader, driver);
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to load JDBC driver " + request.driverClass(),
          exception);
    }
  }

  record LoadedDriver(URLClassLoader classLoader, Driver driver) implements AutoCloseable {

    @Override
    public void close() throws Exception {
      classLoader.close();
    }
  }
}
