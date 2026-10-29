package com.pdfeditor.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.security.api-key=test_external_api_key_111",
        "app.security.ui-api-key=test_ui_api_key_222",
        "app.security.header-name=X-API-KEY"
})
class PathBasedApiKeyAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("UI key on /api/v1/sessions -> 401")
    void testUiKeyOnApiV1SessionsReturns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/sessions")
                        .file(file)
                        .param("mode", "EDIT")
                        .header("X-API-KEY", "test_ui_api_key_222"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("EXTERNAL key on /api/export/redact -> 401")
    void testExternalKeyOnExportRedactReturns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/export/redact")
                        .file(file)
                        .param("edits", "[]")
                        .header("X-API-KEY", "test_external_api_key_111"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("UI key on /api/export/redact -> not 401")
    void testUiKeyOnExportRedactReturnsNot401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/export/redact")
                        .file(file)
                        .param("edits", "[]")
                        .header("X-API-KEY", "test_ui_api_key_222"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }
}

