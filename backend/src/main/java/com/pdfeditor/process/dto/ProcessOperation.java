package com.pdfeditor.process.dto;

public class ProcessOperation {
    private String type;
    private String find;
    private String replace;

    public ProcessOperation() {}

    public ProcessOperation(String type, String find, String replace) {
        this.type = type;
        this.find = find;
        this.replace = replace;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFind() {
        return find;
    }

    public void setFind(String find) {
        this.find = find;
    }

    public String getReplace() {
        return replace;
    }

    public void setReplace(String replace) {
        this.replace = replace;
    }
}

