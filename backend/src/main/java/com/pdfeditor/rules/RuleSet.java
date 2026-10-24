package com.pdfeditor.rules;

import com.pdfeditor.model.DocumentModel;

import java.util.List;

public interface RuleSet {
    List<Match> find(DocumentModel doc);
}
