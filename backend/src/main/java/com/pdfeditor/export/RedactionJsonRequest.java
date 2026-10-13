package com.pdfeditor.export;

import java.util.ArrayList;
import java.util.List;

public class RedactionJsonRequest {

    private String pdfBase64;
    private String filename;
    private List<TextRedactionEdit> edits = new ArrayList<>();

    public RedactionJsonRequest() {
    }

    public RedactionJsonRequest(String pdfBase64, String filename, List<TextRedactionEdit> edits) {
        this.pdfBase64 = pdfBase64;
        this.filename = filename;
        this.edits = edits != null ? edits : new ArrayList<>();
    }

    public String getPdfBase64() {
        return pdfBase64;
    }

    public void setPdfBase64(String pdfBase64) {
        this.pdfBase64 = pdfBase64;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public List<TextRedactionEdit> getEdits() {
        return edits;
    }

    public void setEdits(List<TextRedactionEdit> edits) {
        this.edits = edits != null ? edits : new ArrayList<>();
    }
}
