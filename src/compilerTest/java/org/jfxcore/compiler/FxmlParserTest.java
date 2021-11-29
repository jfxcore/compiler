// Copyright (c) 2021, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.compiler;

import org.jfxcore.compiler.diagnostic.ErrorCode;
import org.jfxcore.compiler.diagnostic.MarkupException;
import org.jfxcore.compiler.parse.FxmlParseAbortException;
import org.jfxcore.compiler.util.TestCompiler;
import org.jfxcore.compiler.util.TestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("HttpUrlsUsage")
@ExtendWith(TestExtension.class)
public class FxmlParserTest {

    @Test
    public void Missing_Xmlns_Aborts_Parsing() {
        assertThrows(
            FxmlParseAbortException.class,
            () -> TestCompiler.newInstance(this, "Missing_Xmlns_Aborts_Parsing", """
                <GridPane xmlns:fx="http://javafx.com/fxml"/>
                """));
    }

    @Test
    public void Unknown_Xmlns_Aborts_Parsing() {
        assertThrows(
            FxmlParseAbortException.class,
            () -> TestCompiler.newInstance(this, "Unknown_Xmlns_Aborts_Parsing", """
                <GridPane xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml"/>
                """));
    }

    @Test
    public void Unmatched_Tags_Throws_Exception() {
        MarkupException ex = assertThrows(MarkupException.class, () -> TestCompiler.newInstance(
            this, "Unmatched_Tags_Throws_Exception", """
                <GridPane xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
                </Button>
                """));

        assertEquals(ErrorCode.UNMATCHED_TAG, ex.getDiagnostic().getCode());
    }

}
