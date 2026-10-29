package com.pdfeditor.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedactionCategoryRegistryTest {

    private RedactionCategoryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RedactionCategoryRegistry();
    }

    @Test
    @DisplayName("Registry lists default categories with id, label, and default=true")
    void testRegistryListsDefaultCategories() {
        List<RedactionCategory> categories = registry.getCategories();
        assertThat(categories).hasSize(2);

        RedactionCategory email = registry.findById("email").orElseThrow();
        assertThat(email.getId()).isEqualTo("email");
        assertThat(email.getLabel()).isEqualTo("Email Addresses");
        assertThat(email.isDefault()).isTrue();
        assertThat(email.getDetector()).isNotNull();

        RedactionCategory phone = registry.findById("phone").orElseThrow();
        assertThat(phone.getId()).isEqualTo("phone");
        assertThat(phone.getLabel()).isEqualTo("Phone Numbers");
        assertThat(phone.isDefault()).isTrue();
        assertThat(phone.getDetector()).isNotNull();
    }

    @Test
    @DisplayName("Category detectors only find their specific PII type")
    void testDetectorsOnlyFindSpecificType() {
        String sample = "Contact: test@example.com or +91 98765 43210";

        RedactionCategory email = registry.findById("email").orElseThrow();
        List<PiiDetector.PiiSpan> emailSpans = email.getDetector().apply(sample);
        assertThat(emailSpans).hasSize(1);
        assertThat(emailSpans.get(0).getType()).isEqualTo("email");
        assertThat(emailSpans.get(0).getMatch()).contains("test@example.com");

        RedactionCategory phone = registry.findById("phone").orElseThrow();
        List<PiiDetector.PiiSpan> phoneSpans = phone.getDetector().apply(sample);
        assertThat(phoneSpans).hasSize(1);
        assertThat(phoneSpans.get(0).getType()).isEqualTo("phone");
        assertThat(phoneSpans.get(0).getMatch()).contains("98765 43210");
    }

    @Test
    @DisplayName("validateCategoryIds accepts valid ids and rejects unknown ids")
    void testValidateCategoryIds() {
        // Valid
        registry.validateCategoryIds(List.of("email", "phone"));
        registry.validateCategoryIds(List.of("EMAIL"));
        registry.validateCategoryIds(Collections.emptyList());
        registry.validateCategoryIds(null);

        // Unknown
        assertThatThrownBy(() -> registry.validateCategoryIds(List.of("ssn")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown redaction category: 'ssn'");

        assertThatThrownBy(() -> registry.validateCategoryIds(List.of("email", "invalid_id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown redaction category: 'invalid_id'");
    }

    @Test
    @DisplayName("parseCategoryIds parses comma-separated, JSON array, and single strings")
    void testParseCategoryIds() {
        assertThat(registry.parseCategoryIds(null)).isNull();
        assertThat(registry.parseCategoryIds(List.of("email,phone"))).containsExactly("email", "phone");
        assertThat(registry.parseCategoryIds(List.of("[\"email\", \"phone\"]"))).containsExactly("email", "phone");
        assertThat(registry.parseCategoryIds(List.of("email", "phone"))).containsExactly("email", "phone");
        assertThat(registry.parseCategoryIds(List.of("phone"))).containsExactly("phone");

        assertThatThrownBy(() -> registry.parseCategoryIds(List.of("email,unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown redaction category: 'unknown'");
    }
}

