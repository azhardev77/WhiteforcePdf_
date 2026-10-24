package com.pdfeditor.rules;

import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.model.DocumentModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "app.security.api-key=test_secret_api_key_xyz"
})
class ResumeRulesTest {

    @Autowired
    private ExtractionPipeline extractionPipeline;

    @Autowired
    private ResumeRules resumeRules;

    @Test
    @DisplayName("ResumeRules finds email and phone in sample resume.pdf")
    void testFindPIIInResumePdf() throws IOException {
        File resumeFile = new File("../sample-pdfs/resume.pdf");
        if (!resumeFile.exists()) {
            resumeFile = new File("sample-pdfs/resume.pdf");
        }
        assertThat(resumeFile).exists();

        DocumentModel doc = extractionPipeline.extract(resumeFile);
        assertThat(doc).isNotNull();

        List<Match> matches = resumeRules.find(doc);
        assertThat(matches).isNotEmpty();

        boolean foundEmail = matches.stream().anyMatch(m -> "email".equals(m.getType()));
        boolean foundPhone = matches.stream().anyMatch(m -> "phone".equals(m.getType()));

        assertThat(foundEmail).isTrue();
        assertThat(foundPhone).isTrue();

        for (Match match : matches) {
            assertThat(match.getBbox()).isNotNull();
            assertThat(match.getBbox()[2]).isGreaterThan(0); // width > 0
            assertThat(match.getBbox()[3]).isGreaterThan(0); // height > 0
            assertThat(match.getLineBboxes()).isNotEmpty();
        }
    }
}
