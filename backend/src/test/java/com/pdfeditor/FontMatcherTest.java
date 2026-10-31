package com.pdfeditor;

import com.pdfeditor.font.FontMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FontMatcherTest {

    private final FontMatcher fontMatcher = new FontMatcher();

    @Test
    @DisplayName("Match default and sans-serif fonts to LiberationSans")
    void testMatchSansSerif() {
        assertThat(fontMatcher.matchFont("Helvetica", false, false)).isEqualTo("LiberationSans-Regular.ttf");
        assertThat(fontMatcher.matchFont("Arial", true, false)).isEqualTo("LiberationSans-Bold.ttf");
        assertThat(fontMatcher.matchFont("Roboto", false, true)).isEqualTo("LiberationSans-Italic.ttf");
        assertThat(fontMatcher.matchFont("Open Sans", true, true)).isEqualTo("LiberationSans-BoldItalic.ttf");
        assertThat(fontMatcher.matchFont(null, false, false)).isEqualTo("LiberationSans-Regular.ttf");
        assertThat(fontMatcher.matchFont("", false, false)).isEqualTo("LiberationSans-Regular.ttf");
    }

    @Test
    @DisplayName("Match serif fonts to LiberationSerif")
    void testMatchSerif() {
        assertThat(fontMatcher.matchFont("Times New Roman", false, false)).isEqualTo("LiberationSerif-Regular.ttf");
        assertThat(fontMatcher.matchFont("Times", true, false)).isEqualTo("LiberationSerif-Bold.ttf");
        assertThat(fontMatcher.matchFont("Georgia", false, true)).isEqualTo("LiberationSerif-Italic.ttf");
        assertThat(fontMatcher.matchFont("Roman", true, true)).isEqualTo("LiberationSerif-BoldItalic.ttf");
        assertThat(fontMatcher.matchFont("Garamond", false, false)).isEqualTo("LiberationSerif-Regular.ttf");
    }

    @Test
    @DisplayName("Match monospaced fonts to LiberationMono")
    void testMatchMonospace() {
        assertThat(fontMatcher.matchFont("Courier", false, false)).isEqualTo("LiberationMono-Regular.ttf");
        assertThat(fontMatcher.matchFont("Courier New", true, false)).isEqualTo("LiberationMono-Bold.ttf");
        assertThat(fontMatcher.matchFont("Consolas", false, true)).isEqualTo("LiberationMono-Italic.ttf");
        assertThat(fontMatcher.matchFont("Monospace", true, true)).isEqualTo("LiberationMono-BoldItalic.ttf");
        assertThat(fontMatcher.matchFont("Source Code Pro", false, false)).isEqualTo("LiberationMono-Regular.ttf");
    }

    @Test
    @DisplayName("Match Devanagari and Hindi fonts to NotoSans fallback")
    void testMatchDevanagariAndHindi() {
        assertThat(fontMatcher.matchFont("Noto Sans Devanagari", false, false)).isEqualTo("NotoSans-Regular.ttf");
        assertThat(fontMatcher.matchFont("Devanagari", true, false)).isEqualTo("NotoSans-Regular.ttf");
        assertThat(fontMatcher.matchFont("Hindi", false, true)).isEqualTo("NotoSans-Regular.ttf");
    }

    @Test
    @DisplayName("Verify resolveFontFilename alias and accessor methods")
    void testResolveFontFilenameAndAccessors() {
        assertThat(fontMatcher.resolveFontFilename("Helvetica", true, false)).isEqualTo("LiberationSans-Bold.ttf");
        assertThat(fontMatcher.getDefaultFontFilename()).isEqualTo("LiberationSans-Regular.ttf");
        assertThat(fontMatcher.getFallbackFontFilename()).isEqualTo("NotoSans-Regular.ttf");
    }
}

