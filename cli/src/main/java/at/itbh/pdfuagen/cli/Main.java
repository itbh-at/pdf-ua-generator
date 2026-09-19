/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point of the command-line interface. */
@Command(
    name = "pdf-ua-generator",
    mixinStandardHelpOptions = true,
    versionProvider = Main.VersionProvider.class,
    subcommands = {
      RenderCommand.class,
      SchemaCommand.class,
      ValidateCommand.class,
      CheckCommand.class,
      VerifyCommand.class
    },
    description = "Renders Qute templates with JSON data into accessible documents.")
public final class Main implements Runnable {

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  public static void main(String[] args) {
    // Batik and ImageIO use AWT; never try to open a display.
    System.setProperty("java.awt.headless", "true");
    System.exit(commandLine().execute(args));
  }

  static CommandLine commandLine() {
    return new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true);
  }

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      String version = Main.class.getPackage().getImplementationVersion();
      return new String[] {"pdf-ua-generator " + (version != null ? version : "development")};
    }
  }
}
