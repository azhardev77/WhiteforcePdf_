package com.pdfeditor.render;

public class HtmlTemplate {

    public static String wrap(String bodyContent, String css, String title) {
        String safeTitle = (title != null && !title.isBlank()) ? escapeHtml(title) : "PDF Export";
        return "<!DOCTYPE html>\n" +
               "<html lang=\"en\">\n" +
               "<head>\n" +
               "    <meta charset=\"UTF-8\">\n" +
               "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
               "    <title>" + safeTitle + "</title>\n" +
               "    <style>\n" +
               (css != null ? css : "") + "\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               (bodyContent != null ? bodyContent : "") + "\n" +
               "</body>\n" +
               "</html>";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
