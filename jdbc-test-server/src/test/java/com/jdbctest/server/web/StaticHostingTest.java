package com.jdbctest.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(StaticHostingTest.Config.class)
@TestPropertySource(properties = "jdbc-test.web.directory=src/test/resources/web")
class StaticHostingTest {
    @Configuration
    @EnableWebMvc
    @Import(StaticWebConfiguration.class)
    static class Config { }

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test void browserNavigationReturnsReactShell() throws Exception {
        mvc.perform(get("/runs/00000000-0000-0000-0000-000000000001").accept("text/html"))
                .andExpect(status().isOk()).andExpect(content().string("<html>React shell</html>\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/drivers", "/connections", "/runs", "/runs/new", "/unknown-client-route"})
    void frontendRoutesReturnShell(String path) throws Exception {
        mvc.perform(get(path).accept("text/html")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test void rootForwardsOnlyToIndex() throws Exception {
        mvc.perform(get("/").accept("text/html")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/unknown", "/actuator", "/actuator/unknown", "/error", "/missing.js", "/assets/missing.js"})
    void backendAndMissingAssetRoutesDoNotReturnShell(String path) throws Exception {
        mvc.perform(get(path).accept("text/html")).andExpect(status().isNotFound());
    }

    @Test void jsonRequestDoesNotReceiveHtml() throws Exception {
        mvc.perform(get("/unknown").accept("application/json")).andExpect(status().isNotFound());
    }

    @Test void hashedAssetIsServedWithImmutableCache() throws Exception {
        mvc.perform(get("/assets/app-hash.js")).andExpect(status().isOk())
                .andExpect(content().string("export {};\n"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }
}
