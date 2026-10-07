package com.jdbctest.server.run;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public final class ExternalIdentityPostgresDriver extends org.postgresql.Driver {
    @Override public Connection connect(String url, Properties properties) throws SQLException {
        ClassLoader loader = org.postgresql.Driver.class.getClassLoader();
        if (!(loader instanceof URLClassLoader) || loader.getParent() != ClassLoader.getPlatformClassLoader()
                || getClass().getClassLoader() != loader) throw new SQLException("PostgreSQL driver is not isolated");
        Connection connection = super.connect(url, properties);
        if (connection == null) return null;
        try {
            Path artifact = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Properties identity = new Properties();
            identity.setProperty("artifact", artifact.toString());
            identity.setProperty("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
            identity.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
            identity.setProperty("driverClass", org.postgresql.Driver.class.getName());
            identity.setProperty("isolated", "true");
            try (var output = Files.newOutputStream(artifact.resolveSibling(artifact.getFileName() + ".identity"))) { identity.store(output, null); }
        } catch (java.io.IOException | java.net.URISyntaxException | java.security.NoSuchAlgorithmException exception) {
            connection.close();
            throw new SQLException("Cannot record external driver identity", exception);
        }
        return connection;
    }
}
