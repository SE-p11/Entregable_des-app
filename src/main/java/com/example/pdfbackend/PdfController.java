package com.example.pdfbackend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/pdf")
@CrossOrigin(origins = "http://localhost:4200")
public class PdfController {

    @PostMapping("/merge")
    public ResponseEntity<byte[]> mergeFiles(
            @RequestParam(value = "tituloPrincipal", required = false) String tituloPrincipal,
            @RequestParam(value = "autor", required = false) String autor,
            @RequestParam(value = "fecha", required = false) String fecha,
            @RequestParam(value = "seccionesJson", required = false) String seccionesJson,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {

        try (ByteArrayOutputStream finalOutputStream = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();

            // 1. Parsear el JSON de secciones dinámicas enviadas desde Angular
            List<SectionDto> secciones = null;
            if (seccionesJson != null && !seccionesJson.trim().isEmpty()) {
                ObjectMapper mapper = new ObjectMapper();
                secciones = mapper.readValue(seccionesJson, new TypeReference<List<SectionDto>>() {});
            }

            // 2. Si se llenó el título o hay secciones dinámicas, generamos la página de la plantilla
            if ((tituloPrincipal != null && !tituloPrincipal.trim().isEmpty()) || (secciones != null && !secciones.isEmpty())) {
                byte[] templatePdfBytes = generateTemplatePdf(tituloPrincipal, autor, fecha, secciones);
                merger.addSource(new RandomAccessReadBuffer(templatePdfBytes));
            }

            // 3. Añadir los archivos adjuntos (PDF, Word, Excel, Imágenes)
            if (files != null) {
                for (MultipartFile file : files) {
                    String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
                    String contentType = file.getContentType();

                    if (filename.endsWith(".pdf") || (contentType != null && contentType.equals("application/pdf"))) {
                        merger.addSource(new RandomAccessReadBuffer(file.getBytes()));
                    } else if (filename.endsWith(".docx")) {
                        merger.addSource(new RandomAccessReadBuffer(convertDocxToPdfBytes(file)));
                    } else if (filename.endsWith(".xlsx")) {
                        merger.addSource(new RandomAccessReadBuffer(convertExcelToPdfBytes(file)));
                    } else if (contentType != null && contentType.startsWith("image/")) {
                        merger.addSource(new RandomAccessReadBuffer(convertImageToPdfBytes(file)));
                    }
                }
            }

            merger.setDestinationStream(finalOutputStream);
            merger.mergeDocuments(null);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "documento_combinado.pdf");

            return ResponseEntity.ok().headers(headers).body(finalOutputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    // Método auxiliar para generar la página PDF con el encabezado y las secciones dinámicas
    private byte[] generateTemplatePdf(String titulo, String autor, String fecha, List<SectionDto> secciones) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream contentStream = new PDPageContentStream(doc, page);

            float yPosition = 750;
            float margin = 50;

            // Imprimir Título Principal
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
            contentStream.beginText();
            contentStream.newLineAtOffset(margin, yPosition);
            contentStream.showText(titulo != null ? titulo : "Informe General");
            contentStream.endText();
            yPosition -= 30;

            // Imprimir Autor y Fecha
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
            contentStream.beginText();
            contentStream.newLineAtOffset(margin, yPosition);
            contentStream.showText("Autor: " + (autor != null ? autor : "No especificado") + " | Fecha: " + (fecha != null ? fecha : ""));
            contentStream.endText();
            yPosition -= 40;

            // Recorrer e imprimir las secciones dinámicas creadas en la web
            if (secciones != null) {
                for (SectionDto sec : secciones) {
                    // Control de salto de página automático si se acaba el espacio
                    if (yPosition < 100) {
                        contentStream.close();
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        contentStream = new PDPageContentStream(doc, page);
                        yPosition = 750;
                    }

                    // Título de la sección
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 13);
                    contentStream.beginText();
                    contentStream.newLineAtOffset(margin, yPosition);
                    contentStream.showText(sec.getTitulo() != null ? sec.getTitulo() : "");
                    contentStream.endText();
                    yPosition -= 18;

                    // Contenido de la sección
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    contentStream.beginText();
                    contentStream.newLineAtOffset(margin, yPosition);
                    contentStream.showText(sec.getContenido() != null ? sec.getContenido() : "");
                    contentStream.endText();
                    yPosition -= 30;
                }
            }

            contentStream.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] convertImageToPdfBytes(MultipartFile file) throws IOException {
        try (PDDocument imageDoc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDImageXObject image = PDImageXObject.createFromByteArray(imageDoc, file.getBytes(), file.getOriginalFilename());
            PDPage page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
            imageDoc.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(imageDoc, page)) {
                contentStream.drawImage(image, 0, 0, image.getWidth(), image.getHeight());
            }

            imageDoc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] convertDocxToPdfBytes(MultipartFile file) throws IOException {
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(file.getBytes()));
             PDDocument pdfDoc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.A4);
            pdfDoc.addPage(page);

            PDPageContentStream contentStream = new PDPageContentStream(pdfDoc, page);
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            contentStream.beginText();
            contentStream.newLineAtOffset(50, 750);

            float yPosition = 750;

            for (XWPFParagraph paragraph : docx.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    text = text.replace("\t", "    ")
                            .replaceAll("[\\p{Cntrl}&&[^\r\n]]", "")
                            .replaceAll("[^\\x00-\\x7F]", "");

                    if (!text.trim().isEmpty()) {
                        if (yPosition < 50) {
                            contentStream.endText();
                            contentStream.close();

                            page = new PDPage(PDRectangle.A4);
                            pdfDoc.addPage(page);
                            contentStream = new PDPageContentStream(pdfDoc, page);
                            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                            contentStream.beginText();
                            contentStream.newLineAtOffset(50, 750);
                            yPosition = 750;
                        }

                        contentStream.showText(text);
                        contentStream.newLineAtOffset(0, -15);
                        yPosition -= 15;
                    }
                }
            }

            contentStream.endText();
            contentStream.close();

            pdfDoc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] convertExcelToPdfBytes(MultipartFile file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getBytes()));
             PDDocument pdfDoc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.A4);
            pdfDoc.addPage(page);

            PDPageContentStream contentStream = new PDPageContentStream(pdfDoc, page);
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 10);
            contentStream.beginText();
            contentStream.newLineAtOffset(40, 750);

            float yPosition = 750;
            DataFormatter formatter = new DataFormatter();

            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                StringBuilder rowText = new StringBuilder();
                for (Cell cell : row) {
                    rowText.append(formatter.formatCellValue(cell)).append(" | ");
                }

                String cleanText = rowText.toString()
                        .replace("\t", "    ")
                        .replaceAll("[\\p{Cntrl}&&[^\r\n]]", "")
                        .replaceAll("[^\\x00-\\x7F]", "");

                if (!cleanText.trim().isEmpty()) {
                    if (yPosition < 50) {
                        contentStream.endText();
                        contentStream.close();

                        page = new PDPage(PDRectangle.A4);
                        pdfDoc.addPage(page);
                        contentStream = new PDPageContentStream(pdfDoc, page);
                        contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                        contentStream.beginText();
                        contentStream.newLineAtOffset(40, 750);
                        yPosition = 750;
                    }

                    contentStream.showText(cleanText);
                    contentStream.newLineAtOffset(0, -14);
                    yPosition -= 14;
                }
            }

            contentStream.endText();
            contentStream.close();

            pdfDoc.save(out);
            return out.toByteArray();
        }
    }
}