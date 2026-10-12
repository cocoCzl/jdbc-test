package com.jdbctest.server.deploy;

import com.jdbctest.server.security.CredentialEncryptionService;
import com.jdbctest.server.security.SecurityProperties;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Acceptance-only probe: decrypt an app-saved credential, with no target connection. */
public class CredentialReadbackProbe {

  @Configuration(proxyBeanMethods = false)
  static class ProbeConfiguration {
  }

  public static void main(String[] args) throws Exception {
    var input = new ObjectMapper().readTree(System.in);
    var application = new SpringApplication(ProbeConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setBannerMode(Banner.Mode.OFF);
    application.setLogStartupInfo(false);
    try (var context = application.run(args)) {
      var encryption = new CredentialEncryptionService(new SecurityProperties(
          context.getEnvironment().getRequiredProperty("jdbc-test.security.master-key")));
      if (!input.path("expected").asText().equals(encryption.decrypt(input.path("envelope").asText()))) {
        throw new IllegalStateException("Acceptance credential mismatch");
      }
      System.out.println("PASS: original saved credential decrypts using the effective persisted Master Key; no target was contacted.");
    }
  }
}
