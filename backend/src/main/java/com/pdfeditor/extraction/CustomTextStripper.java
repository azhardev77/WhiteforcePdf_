package com.pdfeditor.extraction;

import java.util.List;

public class CustomTextStripper extends CharacterCollector {

    public CustomTextStripper() {
        super();
    }

    public CustomTextStripper(List<Glyph> targetList) {
        super(targetList);
    }
}
