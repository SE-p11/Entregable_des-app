package com.example.pdfbackend;

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

@RestController
@RequestMapping("/api/pdf")
@CrossOrigin(origins = "http://localhost:4200")
public class PdfController {

    @PostMapping("/merge")
    public ResponseEntity<byte[]> mergeFiles(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().build();
        }

        try (ByteArrayOutputStream finalOutputStream = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();

            for (MultipartFile file : files) {
                String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
                String contentType = file.getContentType();

                if (filename.endsWith(".pdf") || (contentType != null && contentType.equals("application/pdf"))) {
                    merger.addSource(new RandomAccessReadBuffer(file.getBytes()));
                } else if (filename.endsWith(".docx")) {
                    byte[] docxPdf = convertDocxToPdfBytes(file);
                    merger.addSource(new RandomAccessReadBuffer(docxPdf));
                } else if (filename.endsWith(".xlsx")) {
                    byte[] excelPdf = convertExcelToPdfBytes(file);
                    merger.addSource(new RandomAccessReadBuffer(excelPdf));
                } else if (contentType != null && contentType.startsWith("image/")) {
                    byte[] imagePdf = convertImageToPdfBytes(file);
                    merger.addSource(new RandomAccessReadBuffer(imagePdf));
                }
            }

            merger.setDestinationStream(finalOutputStream);
            merger.mergeDocuments(null);

            byte[] pdfBytes = finalOutputStream.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "documento_combinado.pdf");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
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