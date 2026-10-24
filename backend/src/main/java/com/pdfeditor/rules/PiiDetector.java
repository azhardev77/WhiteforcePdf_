package com.pdfeditor.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII Detection and Automatic Redaction Utility.
 * Java port of frontend/src/utils/piiRedactor.js maintaining 100% parity.
 */
public class PiiDetector {

    public static final String ICON_PREFIX_PATTERN = "(?:[\\u2600-\\u27BF\\uE000-\\uF8FF\\x{1F300}-\\x{1F9FF}✉☎✆📞📧📱📍]\\s*)?";

    public static final String EMAIL_PATTERN_STR = "(?:" + ICON_PREFIX_PATTERN + ")[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
    public static final String PHONE_PATTERN_STR = "(?:" + ICON_PREFIX_PATTERN + ")(?:\\+?\\d{1,3}[\\s-]?)?(?:\\(?\\d{2,5}\\)?[\\s-]?)?\\d{3,5}[\\s-]?\\d{4,6}";

    public static final Pattern EMAIL_REGEX = Pattern.compile(EMAIL_PATTERN_STR);
    public static final Pattern PHONE_REGEX = Pattern.compile(PHONE_PATTERN_STR);

    private static final Pattern MONTH_PREFIX_REGEX = Pattern.compile("(?i)(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s*$");
    private static final Pattern DATE_SUFFIX_REGEX = Pattern.compile("(?i)^\\s*[-–—]\\s*(?:present|current|now|till\\s*date)");
    private static final Pattern YEAR_RANGE_REGEX = Pattern.compile("^(?:19|20)\\d{2}\\s*[-–—/]\\s*(?:19|20)\\d{2}$");
    private static final Pattern STANDALONE_YEAR_REGEX = Pattern.compile("^(?:19|20)\\d{2}$");

    private static final Pattern EMAIL_LABEL_REGEX = Pattern.compile("(?i)(?:(?:email|e-mail)\\s*[:.-]?\\s*|[\\u2600-\\u27BF\\uE000-\\uF8FF\\x{1F300}-\\x{1F9FF}✉☎✆📞📧📱📍]\\s*)+$");
    private static final Pattern PHONE_LABEL_REGEX = Pattern.compile("(?i)(?:(?:phone|tel|telephone|mobile|mob|cell|call|contact)\\s*[:.-]?\\s*|[\\u2600-\\u27BF\\uE000-\\uF8FF\\x{1F300}-\\x{1F9FF}✉☎✆📞📧📱📍]\\s*)+$");

    public static class PiiSpan {
        private int start;
        private int end;
        private String match;
        private String type; // "email" or "phone"

        public PiiSpan(int start, int end, String match, String type) {
            this.start = start;
            this.end = end;
            this.match = match;
            this.type = type;
        }

        public int getStart() {
            return start;
        }

        public void setStart(int start) {
            this.start = start;
        }

        public int getEnd() {
            return end;
        }

        public void setEnd(int end) {
            this.end = end;
        }

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PiiSpan piiSpan = (PiiSpan) o;
            return start == piiSpan.start && end == piiSpan.end &&
                    Objects.equals(match, piiSpan.match) &&
                    Objects.equals(type, piiSpan.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end, match, type);
        }

        @Override
        public String toString() {
            return "PiiSpan{" +
                    "start=" + start +
                    ", end=" + end +
                    ", match='" + match + '\'' +
                    ", type='" + type + '\'' +
                    '}';
        }
    }

    /**
     * Validates whether a matched candidate string is a real phone number
     * and not a year, year range, date range, percentage, or other false positive.
     */
    public static boolean isLikelyPhoneNumber(String matchStr, String fullText, int matchIndex) {
        if (matchStr == null || matchStr.isBlank()) {
            return false;
        }
        String trimmed = matchStr.trim();
        String digitsOnly = trimmed.replaceAll("\\D", "");

        // Valid phone numbers typically have 7 to 15 digits (E.164 max 15)
        if (digitsOnly.length() < 7 || digitsOnly.length() > 15) {
            return false;
        }

        // Reject year ranges like "2020 - 2024", "1998 - 2002"
        if (YEAR_RANGE_REGEX.matcher(trimmed).matches()) {
            return false;
        }

        // Reject standalone 4-digit years
        if (STANDALONE_YEAR_REGEX.matcher(trimmed).matches()) {
            return false;
        }

        // Reject if adjacent to percentage
        String beforeChar = fullText.substring(Math.max(0, matchIndex - 2), matchIndex);
        int afterStart = matchIndex + matchStr.length();
        int afterEnd = Math.min(fullText.length(), afterStart + 3);
        String afterChar = fullText.substring(afterStart, afterEnd);
        if (beforeChar.contains("%") || afterChar.contains("%")) {
            return false;
        }

        // Reject if preceded by month name (e.g., "Feb 2026 - Present" or "Jan 2020 - 2024")
        String textBefore = fullText.substring(Math.max(0, matchIndex - 20), matchIndex);
        if (MONTH_PREFIX_REGEX.matcher(textBefore).find()) {
            return false;
        }

        // Reject if followed by date suffix (e.g., " - Present")
        int suffixEnd = Math.min(fullText.length(), afterStart + 20);
        String textAfter = fullText.substring(afterStart, suffixEnd);
        if (DATE_SUFFIX_REGEX.matcher(textAfter).find()) {
            return false;
        }

        // Reject date formats like MM/YYYY - MM/YYYY or DD/MM/YYYY
        if (trimmed.matches("^\\d{1,2}/\\d{4}.*") || trimmed.matches(".*\\/\\d{4}$")) {
            return false;
        }

        return true;
    }

    /**
     * Finds all PII matches (emails and phone numbers) in the given text.
     * Returns list of PiiSpan sorted by start index with overlapping spans merged.
     */
    public static List<PiiSpan> findPIISpans(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        List<PiiSpan> spans = new ArrayList<>();

        // 1. Scan for emails
        Matcher emailMatcher = EMAIL_REGEX.matcher(text);
        while (emailMatcher.find()) {
            int start = emailMatcher.start();
            int end = emailMatcher.end();

            // Check if preceded by a contact label like "Email: " or "E-mail: " or icon glyphs
            String before = text.substring(0, start);
            Matcher labelMatcher = EMAIL_LABEL_REGEX.matcher(before);
            if (labelMatcher.find()) {
                start = before.length() - labelMatcher.group().length();
            }

            spans.add(new PiiSpan(start, end, text.substring(start, end), "email"));
        }

        // 2. Scan for phone numbers
        Matcher phoneMatcher = PHONE_REGEX.matcher(text);
        while (phoneMatcher.find()) {
            String matchStr = phoneMatcher.group();
            int matchIndex = phoneMatcher.start();
            if (isLikelyPhoneNumber(matchStr, text, matchIndex)) {
                int start = matchIndex;
                int end = phoneMatcher.end();

                // Check if preceded by a contact label like "Phone: ", "Tel: ", etc. or icon glyphs
                String before = text.substring(0, start);
                Matcher labelMatcher = PHONE_LABEL_REGEX.matcher(before);
                if (labelMatcher.find()) {
                    start = before.length() - labelMatcher.group().length();
                }

                spans.add(new PiiSpan(start, end, text.substring(start, end), "phone"));
            }
        }

        // Sort spans by start asc
        spans.sort(Comparator.comparingInt(PiiSpan::getStart));

        // Merge overlapping or adjacent spans
        List<PiiSpan> merged = new ArrayList<>();
        for (PiiSpan span : spans) {
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            PiiSpan prev = merged.get(merged.size() - 1);
            if (span.getStart() <= prev.getEnd()) {
                int newEnd = Math.max(prev.getEnd(), span.getEnd());
                prev.setEnd(newEnd);
                prev.setMatch(text.substring(prev.getStart(), newEnd));
            } else {
                merged.add(span);
            }
        }

        return merged;
    }

    /**
     * Checks whether text contains any email or phone number.
     */
    public static boolean hasPII(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return !findPIISpans(text).isEmpty();
    }

    /**
     * Strips PII from text and cleans up orphaned delimiters and whitespace.
     */
    public static String redactPIIFromText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        List<PiiSpan> spans = findPIISpans(text);
        if (spans.isEmpty()) {
            return text;
        }

        // Cut out the PII spans
        StringBuilder sb = new StringBuilder();
        int lastIndex = 0;
        for (PiiSpan span : spans) {
            sb.append(text, lastIndex, span.getStart());
            lastIndex = span.getEnd();
        }
        sb.append(text.substring(lastIndex));

        String result = sb.toString();

        // 1. Collapse multiple repeated delimiters with optional whitespace between them
        result = result.replaceAll("([|•,\\-–—·/])\\s*(?:[|•,\\-–—·/]\\s*)+", "$1 ");

        // 2. Remove leading delimiter
        result = result.replaceAll("^\\s*[|•,\\-–—·/]\\s*", "");

        // 3. Remove trailing delimiter
        result = result.replaceAll("\\s*[|•,\\-–—·/]\\s*$", "");

        // 4. Clean up any remaining leading/trailing whitespace
        result = result.trim();

        // 5. If the entire remaining text is only whitespace or delimiters or icon glyphs, return ""
        if (result.isEmpty() || result.matches("^[|•,\\-–—·/:\\s\\u2600-\\u27BF\\uE000-\\uF8FF\\x{1F300}-\\x{1F9FF}✉☎✆📞📧📱📍]+$")) {
            return "";
        }

        // Also, if stripping leaves only 1-3 non-alphanumeric chars (e.g. font icon glyph)
        if (!result.matches(".*[a-zA-Z0-9].*") && result.length() <= 3) {
            return "";
        }

        // 6. Clean up internal excessive whitespace
        result = result.replaceAll("\\s{2,}", " ");

        return result;
    }
}
