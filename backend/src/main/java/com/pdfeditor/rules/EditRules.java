package com.pdfeditor.rules;

import com.pdfeditor.model.DocumentModel;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class EditRules implements RuleSet {

    @Override
    public List<Match> find(DocumentModel doc) {
        return Collections.emptyList();
    }
}
