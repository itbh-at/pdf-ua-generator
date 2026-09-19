/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point of the command-line interface. Subcommands are added per phase. */
@Command(name = "pdf-ua-generator", mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        description = "Renders Qute templates with JSON data into accessible documents.")
public final class Main implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = Main.class.getPackage().getImplementationVersion();
            return new String[] { "pdf-ua-generator " + (version != null ? version : "development") };
        }
    }
}
