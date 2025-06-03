package at.itbh.pdfuagen;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.Variant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TemplateRenderService {

    @Inject
    Engine engine;

    public String text(String templateString, Map<String, String> record) throws IOException {
        Template template = engine.parse(templateString,
                Variant.forContentType(Variant.TEXT_PLAIN));
        return template.render(record);
    }

    public void xhtml(File templateFile, Map<String, String> record, File outputFile) throws IOException {
        Template template = engine.parse(Files.readString(templateFile.toPath()),
                Variant.forContentType(Variant.TEXT_HTML));
        Files.writeString(outputFile.toPath(), template.render(record));
    }

    public void plain(File templateFile, Map<String, String> record, File outputFile) throws IOException {
        Files.writeString(outputFile.toPath(), text(Files.readString(templateFile.toPath()), record));
    }

    public void pdf(File templateFile, Map<String, String> params, File outputFile, float pdfVersion) throws IOException {
        File pdfHtmFile = new File(outputFile.getParentFile(), outputFile.getName() + ".xhtml");
        xhtml(templateFile, params, pdfHtmFile);
        try (OutputStream os = new FileOutputStream(outputFile)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());
            builder.usePdfUaAccessibility(true);
            builder.usePdfVersion(pdfVersion);
            builder.withProducer("itbh.at PDF UA Generator");
            builder.withHtmlContent(Files.readString(pdfHtmFile.toPath()), templateFile.toURI().toString());
            builder.toStream(os);
            builder.run();
        }
    }

}
