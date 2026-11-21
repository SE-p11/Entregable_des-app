package com.example.pdfbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Convierte Word / Excel a PDF usando LibreOffice en modo headless.
 * A diferencia de leer solo el texto, conserva imágenes, tablas, gráficos,
 * tildes, colores y todas las hojas del Excel.
 */
@Service
public class LibreOfficeConverter {

    private static final List<String> RUTAS_COMUNES = List.of(
            "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
            "/usr/bin/soffice",
            "/usr/local/bin/soffice",
            "/opt/libreoffice/program/soffice",
            "/Applications/LibreOffice.app/Contents/MacOS/soffice"
    );

    @Value("${libreoffice.path:soffice}")
    private String sofficeConfigurado;

    /** Perfil fijo: arranca más rápido que crear uno nuevo en cada conversión. */
    private final Path perfil = Paths.get(System.getProperty("java.io.tmpdir"), "lo_perfil_pdfbackend");

    /**
     * Convierte los bytes de un documento (docx, doc, xlsx, xls, odt, ods...) a PDF.
     * Es synchronized porque LibreOffice no admite dos conversiones a la vez con el mismo perfil.
     */
    public synchronized byte[] toPdf(byte[] contenido, String extension) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("lo_conv_");
        try {
            Path entrada = dir.resolve("entrada." + extension);
            Files.write(entrada, contenido);

            List<String> cmd = List.of(
                    resolverSoffice(),
                    "-env:UserInstallation=" + perfil.toUri(),
                    "--headless",
                    "--norestore",
                    "--convert-to", "pdf",
                    "--outdir", dir.toString(),
                    entrada.toString()
            );

            Process proceso = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!proceso.waitFor(180, TimeUnit.SECONDS)) {
                proceso.destroyForcibly();
                throw new IOException("LibreOffice tardó demasiado en convertir el archivo.");
            }

            Path pdf = dir.resolve("entrada.pdf");
            if (!Files.exists(pdf)) {
                throw new IOException("LibreOffice no generó el PDF. Salida: " + salida);
            }
            return Files.readAllBytes(pdf);
        } finally {
            borrarCarpeta(dir);
        }
    }

    private String resolverSoffice() throws IOException {
        if (sofficeConfigurado != null && Files.exists(Paths.get(sofficeConfigurado))) {
            return sofficeConfigurado;
        }
        for (String ruta : RUTAS_COMUNES) {
            if (Files.exists(Paths.get(ruta))) {
                return ruta;
            }
        }
        // Último recurso: que esté en el PATH
        return sofficeConfigurado == null || sofficeConfigurado.isBlank() ? "soffice" : sofficeConfigurado;
    }

    private void borrarCarpeta(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
