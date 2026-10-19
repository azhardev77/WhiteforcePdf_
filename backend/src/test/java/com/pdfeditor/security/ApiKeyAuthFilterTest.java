package com.pdfeditor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.config.CorsConfig;
import com.pdfeditor.config.SecurityConfig;
import com.pdfeditor.document.DocumentController;
import com.pdfeditor.document.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DocumentController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@TestPropertySource(properties = {
        "app.security.api-key=test_secret_api_key_xyz",
        "app.security.header-name=X-API-KEY"
})
class ApiKeyAuthFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;

    @Test
    @DisplayName("Should return 401 Unauthorized with JSON error when X-API-KEY header is missing")
    void shouldReturn401WhenApiKeyMissing() throws Exception {
        mockMvc.perform(post("/api/documents/extract-sample")
                        .param("filename", "sample.pdf"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", containsString("Invalid or missing API Key")))
                .andExpect(jsonPath("$.path", is("/api/documents/extract-sample")));
    }

    @Test
    @DisplayName("Should return 401 Unauthorized when X-API-KEY header is wrong")
    void shouldReturn401WhenApiKeyInvalid() throws Exception {
        mockMvc.perform(post("/api/documents/extract-sample")
                        .header("X-API-KEY", "wrong_key_123")
                        .param("filename", "sample.pdf"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", containsString("Invalid or missing API Key")));
    }

    @Test
    @DisplayName("Should allow request through when valid X-API-KEY header is supplied")
    void shouldAllowWhenValidApiKeyProvided() throws Exception {
        mockMvc.perform(post("/api/documents/extract-sample")
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .param("filename", ""))
                // Controller handles the request (returns 400 for empty filename, proving auth succeeded!)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should allow request when API key is provided in lowercase header x-api-key")
    void shouldAllowWhenLowercaseApiKeyHeaderProvided() throws Exception {
        mockMvc.perform(post("/api/documents/extract-sample")
                        .header("x-api-key", "test_secret_api_key_xyz")
                        .param("filename", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should bypass authentication for CORS preflight OPTIONS requests")
    void shouldBypassAuthForOptionsRequest() throws Exception {
        mockMvc.perform(options("/api/documents/extract-sample")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }
}
