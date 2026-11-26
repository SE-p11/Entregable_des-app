package com.example.pdfbackend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/pdf")
@CrossOrigin(origins = "http://localhost:4200")
public class PdfController {

    private static final Locale ES = Locale.forLanguageTag("es-PE");
    private static final MediaType DOCX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final LibreOfficeConverter conversor;
    private final DocxTemplateService plantillaService;
    private final ObjectMapper mapper = new ObjectMapper();

    public PdfController(LibreOfficeConverter conversor, DocxTemplateService plantillaService) {
        this.conversor = conversor;
        this.plantillaService = plantillaService;
    }

    /**
     * Parámetros de texto (todos opcionales):
     *   usarPlantilla = true | false
     *   formato       = pdf | docx   (docx solo aplica con plantilla)
     *   titulo, dosificacion, obra, cantera, solicita, realizadoPor,
     *   lugar, distrito, provincia, departamento, fecha (yyyy-MM-dd)
     *   seccionesJson = [{"titulo":"..","contenido":".."}]
     * Archivos: files (pdf, docx, doc, xlsx, xls, imágenes)
     */
    @PostMapping("/merge")
    public ResponseEntity<byte[]> merge(
            @RequestParam Map<String, String> params,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {

        try {
            boolean usarPlantilla = "true".equalsIgnoreCase(params.get("usarPlantilla"));
            String formato = params.getOrDefault("formato", "pdf").toLowerCase();

            byte[] informeDocx = null;
            if (usarPlantilla) {
                informeDocx = plantillaService.rellenar(valoresPlantilla(params), leerSecciones(params));
            }

            // ---- Salida Word: solo el informe editado ----
            if (formato.equals("docx")) {
                if (informeDocx == null) {
                    return ResponseEntity.badRequest().build();
                }
                return respuesta(informeDocx, DOCX, "informe_editado.docx");
            }

            // ---- Salida PDF: informe + todos los adjuntos, en el orden recibido ----
            PDFMergerUtility merger = new PDFMergerUtility();
            int fuentes = 0;

            if (informeDocx != null) {
                merger.addSource(new RandomAccessReadBuffer(conversor.toPdf(informeDocx, "docx")));
                fuentes++;
            }

            if (files != null) {
                for (MultipartFile file : files) {
                    byte[] pdf = convertirAPdf(file);
                    if (pdf != null) {
                        merger.addSource(new RandomAccessReadBuffer(pdf));
                        fuentes++;
                    }
                }
            }

            if (fuentes == 0) {
                return ResponseEntity.badRequest().build();
            }

            try (ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
                merger.setDestinationStream(salida);
                merger.mergeDocuments(null);
                return respuesta(salida.toByteArray(), MediaType.APPLICATION_PDF, "documento_combinado.pdf");
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    // ------------------------------------------------------------------

    private byte[] convertirAPdf(MultipartFile file) throws IOException, InterruptedException {
        String nombre = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String tipo = file.getContentType() == null ? "" : file.getContentType();

        if (nombre.endsWith(".pdf") || tipo.equals("application/pdf")) {
            return file.getBytes();
        }
        for (String ext : List.of("docx", "doc", "xlsx", "xls", "odt", "ods")) {
            if (nombre.endsWith("." + ext)) {
                return conversor.toPdf(file.getBytes(), ext);
            }
        }
        if (tipo.startsWith("image/")) {
            return imagenAPdf(file);
        }
        return null; // tipo no soportado: se ignora
    }

    /** Imagen centrada en una hoja A4 (horizontal si la imagen es apaisada), sin deformarla. */
    private byte[] imagenAPdf(MultipartFile file) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, file.getBytes(), file.getOriginalFilename());

            PDRectangle a4 = PDRectangle.A4;
            PDRectangle hoja = img.getWidth() > img.getHeight()
                    ? new PDRectangle(a4.getHeight(), a4.getWidth()) : a4;

            float margen = 30f;
            float escala = Math.min((hoja.getWidth() - 2 * margen) / img.getWidth(),
                                    (hoja.getHeight() - 2 * margen) / img.getHeight());
            float w = img.getWidth() * escala;
            float h = img.getHeight() * escala;

            PDPage page = new PDPage(hoja);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, (hoja.getWidth() - w) / 2, (hoja.getHeight() - h) / 2, w, h);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private Map<String, String> valoresPlantilla(Map<String, String> p) {
        Map<String, String> v = new HashMap<>();
        v.put("TITULO", mayus(p.get("titulo")));
        v.put("DOSIFICACION", limpiar(p.get("dosificacion")));
        v.put("OBRA", mayus(p.get("obra")));
        v.put("CANTERA", mayus(p.get("cantera")));
        v.put("SOLICITA", mayus(p.get("solicita")));
        v.put("REALIZADO_POR", mayus(p.get("realizadoPor")));
        v.put("LUGAR", mayus(p.get("lugar")));
        v.put("DISTRITO", mayus(p.get("distrito")));
        v.put("PROVINCIA", mayus(p.get("provincia")));
        v.put("DEPARTAMENTO", mayus(p.get("departamento")));
        v.put("FECHA", fechaTexto(p.get("fecha")));
        return v;
    }

    private List<SectionDto> leerSecciones(Map<String, String> p) throws IOException {
        String json = p.get("seccionesJson");
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, new TypeReference<List<SectionDto>>() {});
    }

    /** "2026-05-20" -> "Mayo del 2026" */
    private String fechaTexto(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            LocalDate d = LocalDate.parse(iso);
            String mes = d.getMonth().getDisplayName(TextStyle.FULL, ES);
            return Character.toUpperCase(mes.charAt(0)) + mes.substring(1) + " del " + d.getYear();
        } catch (Exception e) {
            return iso;
        }
    }

    private String limpiar(String s) {
        return s == null ? "" : s.replaceAll("\\R", " ").trim();
    }

    private String mayus(String s) {
        return limpiar(s).toUpperCase(ES);
    }

    private ResponseEntity<byte[]> respuesta(byte[] datos, MediaType tipo, String nombre) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(tipo);
        headers.setContentDispositionFormData("attachment", nombre);
        return ResponseEntity.ok().headers(headers).body(datos);
    }
}
