package at.itbh.pdfuagen;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;

import io.quarkus.runtime.Quarkus;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "build", mixinStandardHelpOptions = true)
public class BuildCommand implements Runnable {

    @Parameters(paramLabel = "<source file>", description = "The XHTML source file to be converted to PDF/UA")
    File sourceFile;

    @Option(names = { "-p", "--params" }, description = "Template variables as Key=value map entries", split = ",")
    Map<String, String> params;

    @Option(names = { "-w", "--watch" }, description = "Watch the source file for changes and rebuild on change")
    boolean watch;

    @Option(names = { "-v",
            "--pdf-version" }, description = "The PDF version to be used. Possible values are 1.4, 1.5, 1.6, 1.7 and 2.0. It default to version 2.0.", defaultValue = "2.0")
    float pdfVersion;

    @Inject
    TemplateRenderService renderService;

    @Override
    public void run() {
        try {
            if (watch) {
                System.out.println("Watching " + sourceFile.getName() + " for changes...");
                WatchService watchService = FileSystems.getDefault().newWatchService();
                Path.of(sourceFile.getParent()).register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
                while (true) {
                    WatchKey key = watchService.take(); // blocks until events are present

                    for (WatchEvent<?> event : key.pollEvents()) {

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path fileName = ev.context();

                        if (fileName.toString().equals(sourceFile.getName())) {
                            System.out.println("Rebuilding....");
                            renderService.pdf(sourceFile, params,
                                    new File(sourceFile.getParent(), replaceExtension(sourceFile.getName(), ".pdf")),
                                    pdfVersion);
                        }

                    }

                    boolean valid = key.reset();
                    if (!valid) {
                        break;
                    }
                }
            } else {
                renderService.pdf(sourceFile, params,
                        new File(sourceFile.getParent(), replaceExtension(sourceFile.getName(), ".pdf")), pdfVersion);
            }
        } catch (InterruptedException e) {
            Quarkus.asyncExit();
        } catch (IOException e) {
            e.printStackTrace();
            Quarkus.asyncExit(1);
        }
    }

    public static String replaceExtension(String filename, String newExtension) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1) {
            return filename.substring(0, dotIndex) + newExtension;
        } else {
            return filename + newExtension;
        }
    }

}
