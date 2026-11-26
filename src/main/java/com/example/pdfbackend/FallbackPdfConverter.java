package com.example.pdfbackend;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversor de emergencia: genera un PDF legible a partir de un DOCX usando
 * solo Apache POI + PDFBox, sin necesidad de LibreOffice.
 *
 * El diseño del informe es más simple que el de LibreOffice, pero garantiza
 * que el endpoint siempre devuelva un PDF cuando no se puede instalar
 * LibreOffice (por ejemplo en algunos hostings).
 */
@Component
public class FallbackPdfConverter {

    private static final float MARGEN = 48f;
    private static final float ANCHO = PDRectangle.A4.getWidth() - 2 * MARGEN;
    private static final float ALTO = PDRectangle.A4.getHeight() - 2 * MARGEN;

    public byte[] convertir(byte[] contenido, String extension) throws IOException {
        if (contenido == null || contenido.length == 0) {
            throw new IOException("El documento está vacío, no se puede generar el PDF.");
        }
        if (!"docx".equalsIgnoreCase(extension)) {
            throw new IOException("Sin LibreOffice solo se puede convertir DOCX. Instala LibreOffice para "
                    + extension.toUpperCase() + ".");
        }

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contenido));
             PDDocument pdf = new PDDocument();
             ByteArrayOutputStream salida = new ByteArrayOutputStream()) {

            Escritor escritor = new Escritor(pdf);

            for (IBodyElement elemento : doc.getBodyElements()) {
                if (elemento instanceof XWPFParagraph parrafo) {
                    escritor.linea(parrafo.getText(), 11f);
                } else if (elemento instanceof XWPFTable tabla) {
                    for (XWPFTableRow fila : tabla.getRows()) {
                        StringBuilder celdaTexto = new StringBuilder();
                        for (XWPFTableCell celda : fila.getTableCells()) {
                            if (celdaTexto.length() > 0) celdaTexto.append("   |   ");
                            celdaTexto.append(celda.getText().replace('\n', ' '));
                        }
                        escritor.linea(celdaTexto.toString(), 9f);
                    }
                    escritor.salto(6f);
                }
            }

            // Las fotos van al final, una por página, para no perder ninguna.
            for (XWPFPictureData imagen : doc.getAllPictures()) {
                escritor.imagen(imagen.getData());
            }
            escritor.cerrar();

            pdf.save(salida);
            return salida.toByteArray();
        }
    }

    /** Pequeño escritor que controla saltos de página, líneas e imágenes. */
    private static final class Escritor {
        private final PDDocument pdf;
        private final PDType1Font normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font negrita = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream flujo;
        private float y;

        Escritor(PDDocument pdf) throws IOException {
            this.pdf = pdf;
            nuevaPagina();
        }

        private void nuevaPagina() throws IOException {
            if (flujo != null) flujo.close();
            PDPage pagina = new PDPage(PDRectangle.A4);
            pdf.addPage(pagina);
            flujo = new PDPageContentStream(pdf, pagina);
            y = PDRectangle.A4.getHeight() - MARGEN;
        }

        void salto(float alto) throws IOException {
            y -= alto;
            if (y < MARGEN) nuevaPagina();
        }

        void linea(String texto, float tamano) throws IOException {
            String limpio = limpio(texto);
            if (limpio.isEmpty()) {
                salto(tamano * 0.6f);
                return;
            }
            for (String linea : envolver(limpio, normal, tamano, ANCHO)) {
                if (y < MARGEN + tamano) nuevaPagina();
                flujo.setFont(normal, tamano);
                flujo.beginText();
                flujo.newLineAtOffset(MARGEN, y);
                flujo.showText(linea);
                flujo.endText();
                y -= tamano * 1.35f;
            }
        }

        void imagen(byte[] bytes) throws IOException {
            PDImageXObject img = PDImageXObject.createFromByteArray(pdf, bytes, "foto");
            nuevaPagina();
            float escala = Math.min(ANCHO / img.getWidth(), ALTO / img.getHeight());
            float w = img.getWidth() * escala;
            float h = img.getHeight() * escala;
            flujo.drawImage(img, (PDRectangle.A4.getWidth() - w) / 2,
                    (PDRectangle.A4.getHeight() - h) / 2, w, h);
        }

        void cerrar() throws IOException {
            if (flujo != null) {
                flujo.close();
                flujo = null;
            }
        }

        private static String limpio(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (c == '\n' || c == '\r' || c == '\t' || c == '\u00A0') sb.append(' ');
                else if (c < 32 || c > 255) sb.append('?');
                else sb.append(c);
            }
            return sb.toString().replaceAll("\\s+", " ").trim();
        }

        private static List<String> envolver(String texto, PDType1Font fuente, float tamano, float anchoMax)
                throws IOException {
            List<String> lineas = new ArrayList<>();
            StringBuilder actual = new StringBuilder();
            for (String palabra : texto.split(" ")) {
                String candidata = actual.length() == 0 ? palabra : actual + " " + palabra;
                if (ancho(candidata, fuente, tamano) <= anchoMax || actual.length() == 0) {
                    actual = new StringBuilder(candidata);
                } else {
                    lineas.add(actual.toString());
                    actual = new StringBuilder(palabra);
                }
            }
            if (actual.length() > 0) lineas.add(actual.toString());
            return lineas;
        }

        private static float ancho(String s, PDType1Font fuente, float tamano) throws IOException {
            return fuente.getStringWidth(s) / 1000f * tamano;
        }
    }
}
