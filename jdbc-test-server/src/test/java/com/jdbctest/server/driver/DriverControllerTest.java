package com.jdbctest.server.driver;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DriverControllerTest {

    @Test
    void listsFormSuggestionsWithoutLoadingPackagesOrAdapters() throws Exception {
        DriverService service = mock(DriverService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DriverController(service))
            .setMessageConverters(new JacksonJsonHttpMessageConverter()).build();
        mvc.perform(get("/api/drivers/presets")).andExpect(status().isOk())
            .andExpect(content().json("""
                [
                  {"code":"postgresql","nameKey":"driverPreset.postgresql",
                   "vendor":"PostgreSQL","driverClass":"org.postgresql.Driver",
                   "urlPrefix":"jdbc:postgresql:"},
                  {"code":"mysql","nameKey":"driverPreset.mysql","vendor":"MySQL",
                   "driverClass":"com.mysql.cj.jdbc.Driver","urlPrefix":"jdbc:mysql:"},
                  {"code":"oracle","nameKey":"driverPreset.oracle","vendor":"Oracle",
                   "driverClass":"oracle.jdbc.OracleDriver","urlPrefix":"jdbc:oracle:"},
                  {"code":"sqlserver","nameKey":"driverPreset.sqlserver","vendor":"Microsoft",
                   "driverClass":"com.microsoft.sqlserver.jdbc.SQLServerDriver",
                   "urlPrefix":"jdbc:sqlserver:"},
                  {"code":"kingbase","nameKey":"driverPreset.kingbase","vendor":"Kingbase",
                   "driverClass":"com.kingbase8.Driver","urlPrefix":"jdbc:kingbase8:"},
                  {"code":"custom","nameKey":"driverPreset.custom","vendor":"",
                   "driverClass":"","urlPrefix":""}
                ]
                """, org.springframework.test.json.JsonCompareMode.STRICT));
        verifyNoInteractions(service);
    }
}
