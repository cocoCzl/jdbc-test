package com.jdbctest.server.driver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class RestErrorHandlerTest {

    @Test
    void disconnectedSseDoesNotAttemptASecondJsonResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DisconnectedController())
            .setControllerAdvice(new RestErrorHandler()).build();
        mvc.perform(get("/disconnected"))
            .andExpect(content().string(""));
        mvc.perform(get("/wrapped-disconnect"))
            .andExpect(content().string(""));
        mvc.perform(get("/platform-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @RestController
    static class DisconnectedController {

        @GetMapping("/disconnected")
        void disconnected(HttpServletResponse response) throws java.io.IOException {
            response.setContentType("text/event-stream");
            response.flushBuffer();
            throw new AsyncRequestNotUsableException("Synthetic closed async response");
        }

        @GetMapping("/platform-error")
        void platformError() {
            throw new IllegalStateException("Synthetic platform failure");
        }

        @GetMapping("/wrapped-disconnect")
        void wrappedDisconnect() {
            throw new org.springframework.http.converter.HttpMessageNotWritableException(
                "Synthetic closed stream", new java.io.IOException("Broken pipe"));
        }
    }
}
