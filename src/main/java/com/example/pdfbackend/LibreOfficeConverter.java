package com.example.pdfbackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Convierte Word / Excel a PDF usando LibreOffice en modo headless.
 * Conserva imágenes, tablas, gráficos, tildes, colores y todas las hojas del Excel.
 *
 * Si LibreOffice no está instalado (o falla), se usa {@link FallbackPdfConverter}
 * para que el endpoint siempre devuelva un PDF válido.
 */
@Service
public class LibreOfficeConverter {

    private static final Logger log = LoggerFactory.getLogger(LibreOfficeConverter.class);

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

    @Value("${libreoffice.enabled:true}")
    private boolean libreofficeHabilitado;

    private final FallbackPdfConverter respaldo;

    /** Perfil fijo: arranca más rápido que crear uno nuevo en cada conversión. */
    private final Path perfil = Paths.get(System.getProperty("java.io.tmpdir"), "lo_perfil_pdfbackend");

    public LibreOfficeConverter(FallbackPdfConverter respaldo) {
        this.respaldo = respaldo;
    }

    /** true cuando hay un ejecutable de LibreOffice utilizable. */
    public boolean isDisponible() {
        return resolverSoffice() != null;
    }

    /**
     * Convierte los bytes de un documento (docx, doc, xlsx, xls, odt, ods...) a PDF.
     * Es synchronized porque LibreOffice no admite dos conversiones a la vez con el mismo perfil.
     */
    public synchronized byte[] toPdf(byte[] contenido, String extension) throws IOException, InterruptedException {
        String soffice = resolverSoffice();
        if (soffice == null) {
            log.warn("LibreOffice no encontrado. Se genera el PDF con el conversor interno (formato básico).");
            return respaldo.convertir(contenido, extension);
        }

        Path dir = Files.createTempDirectory("lo_conv_");
        try {
            Path entrada = dir.resolve("entrada." + extension);
            Files.write(entrada, contenido);

            List<String> cmd = List.of(
                    soffice,
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
            byte[] generado = Files.readAllBytes(pdf);
            if (!esPdf(generado)) {
                throw new IOException("LibreOffice generó un archivo que no es un PDF. Salida: " + salida);
            }
            return generado;
        } catch (IOException e) {
            log.warn("Fallo la conversión con LibreOffice ({}). Se usa el conversor interno.", e.getMessage());
            return respaldo.convertir(contenido, extension);
        } finally {
            borrarCarpeta(dir);
        }
    }

    private static boolean esPdf(byte[] bytes) {
        return bytes.length > 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-';
    }

    /** Busca soffice en la configuración, en variable de entorno, en rutas típicas y en el PATH. */
    private String resolverSoffice() {
        if (!libreofficeHabilitado) return null;
        String entorno = System.getenv("LIBREOFFICE_PATH");
        if (entorno != null && !entorno.isBlank() && Files.isExecutable(Paths.get(entorno))) {
            return entorno;
        }
        if (sofficeConfigurado != null && !sofficeConfigurado.isBlank()
                && !"soffice".equalsIgnoreCase(sofficeConfigurado)) {
            Path configurado = Paths.get(sofficeConfigurado);
            if (Files.isExecutable(configurado)) return sofficeConfigurado;
        }
        for (String ruta : RUTAS_COMUNES) {
            if (Files.isExecutable(Paths.get(ruta))) return ruta;
        }
        String nombre = sofficeConfigurado == null || sofficeConfigurado.isBlank()
                ? "soffice" : sofficeConfigurado;
        return buscarEnPath(nombre);
    }

    private static String buscarEnPath(String nombre) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String parte : path.split(Pattern.quote(File.pathSeparator))) {
            if (parte.isBlank()) continue;
            Path candidato = Paths.get(parte, nombre);
            if (Files.isExecutable(candidato)) return candidato.toString();
            if (!nombre.toLowerCase().endsWith(".exe")) {
                Path exe = candidato.resolveSibling(nombre + ".exe");
                if (Files.isExecutable(exe)) return exe.toString();
            }
        }
        return null;
    }

    private void borrarCarpeta(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
