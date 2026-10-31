package com.pdfeditor.font;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Service to resolve embedded font family names and styles (bold, italic)
 * to bundled open-source TTF fonts:
 * - Liberation Sans (default sans-serif, Arial, Helvetica)
 * - Liberation Serif (Times, Roman, Serif)
 * - Liberation Mono (Courier, Monospace, Consolas, Code)
 * - Noto Sans (Devanagari, Hindi, Unicode fallback)
 */
@Service
public class FontMatcher {

    private static final Logger log = LoggerFactory.getLogger(FontMatcher.class);

    public static final String DEFAULT_FONT = "LiberationSans-Regular.ttf";
    public static final String FALLBACK_FONT = "NotoSans-Regular.ttf";

    /**
     * Resolves an embedded font family name and typography style to the matching bundled font filename.
     *
     * @param fontFamily The font family name from PDF metadata or CSS (e.g. "Helvetica", "Times New Roman", "Courier")
     * @param bold       Whether the font style is bold
     * @param italic     Whether the font style is italic
     * @return The corresponding TTF font resource filename (e.g. "LiberationSans-Bold.ttf")
     */
    public String matchFont(String fontFamily, boolean bold, boolean italic) {
        String fn = fontFamily != null ? fontFamily.toLowerCase(Locale.ROOT).trim() : "";
        String style = (bold && italic) ? "-BoldItalic.ttf" : bold ? "-Bold.ttf" : italic ? "-Italic.ttf" : "-Regular.ttf";

        if (fn.contains("devanagari") || fn.contains("hindi") || fn.contains("noto")) {
            return FALLBACK_FONT;
        } else if (fn.contains("times") || fn.contains("serif") || fn.contains("roman") || fn.contains("georgia") || fn.contains("garamond") || fn.contains("palatino")) {
            return "LiberationSerif" + style;
        } else if (fn.contains("courier") || fn.contains("mono") || fn.contains("consolas") || fn.contains("menlo") || fn.contains("source code") || fn.contains("code")) {
            return "LiberationMono" + style;
        } else {
            // Default Sans-Serif (Helvetica, Arial, Trebuchet, Roboto, Open Sans, etc.)
            return "LiberationSans" + style;
        }
    }

    /**
     * Delegate alias matching PdfRedactionService's method name.
     */
    public String resolveFontFilename(String fontFamily, boolean bold, boolean italic) {
        return matchFont(fontFamily, bold, italic);
    }

    /**
     * Returns the default bundled font filename.
     */
    public String getDefaultFontFilename() {
        return DEFAULT_FONT;
    }

    /**
     * Returns the fallback font filename for Devanagari/Hindi or unicode glyphs not in Latin fonts.
     */
    public String getFallbackFontFilename() {
        return FALLBACK_FONT;
    }
}

