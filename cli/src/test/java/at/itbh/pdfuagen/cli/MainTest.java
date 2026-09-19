/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class MainTest {

    @Test
    void helpExitsWithZeroAndPrintsUsage() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));

        assertEquals(0, cmd.execute("--help"));
        assertTrue(out.toString().contains("Usage: pdf-ua-generator"));
    }
}
