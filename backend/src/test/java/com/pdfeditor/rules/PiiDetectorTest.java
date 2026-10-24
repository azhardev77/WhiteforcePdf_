package com.pdfeditor.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiDetectorTest {

    @Test
    @DisplayName("Email detection finds standard emails")
    void testStandardEmailDetection() {
        String text = "Contact me at john.doe@example.com for inquiries.";
        List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(text);
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getType()).isEqualTo("email");
        assertThat(spans.get(0).getMatch()).isEqualTo("john.doe@example.com");
    }

    @Test
    @DisplayName("Email with icon prefix or label absorbs prefix")
    void testEmailWithPrefix() {
        String text1 = "Email: contact@company.org";
        List<PiiDetector.PiiSpan> spans1 = PiiDetector.findPIISpans(text1);
        assertThat(spans1).hasSize(1);
        assertThat(spans1.get(0).getMatch()).isEqualTo("Email: contact@company.org");

        String text2 = "✉ user@domain.in";
        List<PiiDetector.PiiSpan> spans2 = PiiDetector.findPIISpans(text2);
        assertThat(spans2).hasSize(1);
        assertThat(spans2.get(0).getMatch()).contains("user@domain.in");

        String text3 = "📧 azhardev77@gmail.com";
        List<PiiDetector.PiiSpan> spans3 = PiiDetector.findPIISpans(text3);
        assertThat(spans3).hasSize(1);
        assertThat(spans3.get(0).getMatch()).contains("azhardev77@gmail.com");
    }

    @Test
    @DisplayName("Phone detection finds +91 numbers and formatted phone numbers")
    void testPhoneDetection() {
        String t1 = "+91-7747047876";
        List<PiiDetector.PiiSpan> spans1 = PiiDetector.findPIISpans(t1);
        assertThat(spans1).hasSize(1);
        assertThat(spans1.get(0).getType()).isEqualTo("phone");
        assertThat(spans1.get(0).getMatch()).isEqualTo("+91-7747047876");

        String t2 = "+91 98765 43210";
        List<PiiDetector.PiiSpan> spans2 = PiiDetector.findPIISpans(t2);
        assertThat(spans2).hasSize(1);
        assertThat(spans2.get(0).getType()).isEqualTo("phone");

        String t3 = "(123) 456-7890";
        List<PiiDetector.PiiSpan> spans3 = PiiDetector.findPIISpans(t3);
        assertThat(spans3).hasSize(1);

        String t4 = "Phone: +1-555-123-4567";
        List<PiiDetector.PiiSpan> spans4 = PiiDetector.findPIISpans(t4);
        assertThat(spans4).hasSize(1);
        assertThat(spans4.get(0).getMatch()).isEqualTo("Phone: +1-555-123-4567");

        String t5 = "📞 +91-7747047876";
        List<PiiDetector.PiiSpan> spans5 = PiiDetector.findPIISpans(t5);
        assertThat(spans5).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Software Engineer (2020 - 2024)",
            "Worked from 1998 - 2002 at Acme",
            "Tenure: Feb 2026 - Present",
            "From Jan 2020 - 2024",
            "Graduated in 2023 with honors",
            "Score: 95% on exam",
            "Dates: 05/2020 - 08/2023",
            "Short code 12345"
    })
    @DisplayName("False positive filters reject years, date ranges, and percentages")
    void testFalsePositivesRejected(String text) {
        List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(text);
        assertThat(spans).isEmpty();
        assertThat(PiiDetector.hasPII(text)).isFalse();
    }

    @Test
    @DisplayName("redactPIIFromText cleans up delimiters properly")
    void testRedactPIIFromText() {
        String input1 = "Mohd Azhar | Jabalpur | +91-7747047876 | azhardev77@gmail.com";
        String redacted1 = PiiDetector.redactPIIFromText(input1);
        assertThat(redacted1).isEqualTo("Mohd Azhar | Jabalpur");

        String input2 = "Jabalpur   +91-7747047876";
        String redacted2 = PiiDetector.redactPIIFromText(input2);
        assertThat(redacted2).isEqualTo("Jabalpur");

        String input3 = "azharmansoori725@gmail.com";
        String redacted3 = PiiDetector.redactPIIFromText(input3);
        assertThat(redacted3).isEmpty();

        String input4 = "• +91-7747047876 •";
        String redacted4 = PiiDetector.redactPIIFromText(input4);
        assertThat(redacted4).isEmpty();
    }
}
