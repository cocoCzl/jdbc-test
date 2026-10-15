package com.jdbctest.extension;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks Oracle's optional components in the selected driver loader, never the application loader.
 */
public final class OracleDriverDependencies {

  private OracleDriverDependencies() {
  }

  public static boolean appliesTo(String driverClass) {
    return driverClass != null && driverClass.startsWith("oracle.jdbc.");
  }

  public static List<String> warnings(String driverClass, ClassLoader loader) {
    if (!appliesTo(driverClass)) {
      return List.of();
    }
    List<String> warnings = new ArrayList<>();
    if (!available(loader, "oracle.i18n.text.converter.CharacterConverterOGS")) {
      warnings.add("ORACLE_I18N_MISSING");
    }
    if (!xmlAvailable(loader)) {
      warnings.add("ORACLE_XML_MISSING");
    }
    return List.copyOf(warnings);
  }

  /** Only SELECTs and in-memory conversions; call before namespace or fixture creation. */
  public static void preflight(Connection connection, ClassLoader loader, boolean requireSqlxml) {
    if (requireSqlxml && !xmlAvailable(loader)) {
      throw new IllegalStateException("ORACLE_XML_MISSING: SQLXML requires compatible xdb.jar and "
          + "xmlparserv2.jar in the same driver package; upload a new complete package");
    }
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT NLS_CHARSET_ID(VALUE) "
            + "FROM NLS_DATABASE_PARAMETERS WHERE PARAMETER IN "
            + "('NLS_CHARACTERSET', 'NLS_NCHAR_CHARACTERSET')")) {
      int count = 0;
      while (rows.next()) {
        int charset = rows.getInt(1);
        if (rows.wasNull() || charset <= 0) {
          throw new IllegalStateException("ORACLE_CHARSET_PREFLIGHT_FAILED: invalid charset ID");
        }
        checkCharset(loader, charset);
        count++;
      }
      if (count != 2) {
        throw new IllegalStateException("ORACLE_CHARSET_PREFLIGHT_FAILED: expected both charsets");
      }
    } catch (SQLException error) {
      throw new IllegalStateException("ORACLE_CHARSET_PREFLIGHT_FAILED: cannot read target charsets",
          error);
    }
  }

  private static void checkCharset(ClassLoader loader, int charset) {
    try {
      Class<?> type = Class.forName("oracle.sql.CharacterSet", true, loader);
      Object instance = type.getMethod("make", int.class).invoke(null, charset);
      type.getMethod("convert", String.class).invoke(instance, "jdbc_test");
    } catch (InvocationTargetException error) {
      throw new IllegalStateException("ORACLE_CHARSET_PREFLIGHT_FAILED: charset " + charset
          + " cannot be converted by this driver package; include compatible orai18n.jar",
          error.getCause());
    } catch (ReflectiveOperationException | LinkageError error) {
      throw new IllegalStateException("ORACLE_CHARSET_PREFLIGHT_FAILED: cannot inspect Oracle "
          + "charset support; check the driver and companion JAR versions", error);
    }
  }

  private static boolean xmlAvailable(ClassLoader loader) {
    return available(loader, "oracle.xdb.XMLType")
        && available(loader, "oracle.xml.parser.v2.DOMParser");
  }

  private static boolean available(ClassLoader loader, String name) {
    try {
      // Resolving method signatures also detects missing dependencies of a present class.
      Class.forName(name, false, loader).getDeclaredMethods();
      return true;
    } catch (ClassNotFoundException | LinkageError error) {
      return false;
    }
  }
}
