package com.example.pdfbackend;

import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Rellena la plantilla Word (resources/plantillas/informe_afirmado.docx).
 * Los marcadores tienen la forma {{OBRA}}, {{CANTERA}}, etc. y cada uno está
 * en su propio "run" del Word, así que se reemplazan sin perder el formato.
 */
@Service
public class DocxTemplateService {

    private static final String PLANTILLA = "plantillas/informe_afirmado.docx";

    public static final List<String> CLAVES = List.of(
            "TITULO", "DOSIFICACION", "OBRA", "CANTERA", "SOLICITA", "REALIZADO_POR",
            "LUGAR", "DISTRITO", "PROVINCIA", "DEPARTAMENTO", "FECHA");

    public byte[] rellenar(Map<String, String> valores, List<SectionDto> secciones) throws IOException {
        try (InputStream is = new ClassPathResource(PLANTILLA).getInputStream();
             XWPFDocument doc = new XWPFDocument(is);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            reemplazarEnParrafos(doc.getParagraphs(), valores);
            for (XWPFTable tabla : doc.getTables()) {
                for (XWPFTableRow fila : tabla.getRows()) {
                    for (XWPFTableCell celda : fila.getTableCells()) {
                        reemplazarEnParrafos(celda.getParagraphs(), valores);
                    }
                }
            }

            agregarSecciones(doc, secciones);

            doc.write(out);
            return out.toByteArray();
        }
    }

    private void reemplazarEnParrafos(List<XWPFParagraph> parrafos, Map<String, String> valores) {
        for (XWPFParagraph p : parrafos) {
            for (XWPFRun run : p.getRuns()) {
                String texto = run.getText(0);
                if (texto == null || !texto.contains("{{")) {
                    continue;
                }
                for (String clave : CLAVES) {
                    texto = texto.replace("{{" + clave + "}}", valores.getOrDefault(clave, ""));
                }
                run.setText(texto, 0);
            }
        }
    }

    /** Secciones libres escritas en la web: se agregan al final del informe, en página nueva. */
    private void agregarSecciones(XWPFDocument doc, List<SectionDto> secciones) {
        if (secciones == null || secciones.isEmpty()) {
            return;
        }
        boolean primera = true;
        for (SectionDto sec : secciones) {
            XWPFParagraph titulo = doc.createParagraph();
            titulo.setSpacingAfter(120);
            XWPFRun rt = titulo.createRun();
            if (primera) {
                rt.addBreak(BreakType.PAGE);
                primera = false;
            }
            rt.setBold(true);
            rt.setFontSize(14);
            rt.setFontFamily("Arial");
            rt.setText(sec.getTitulo() == null ? "" : sec.getTitulo());

            XWPFParagraph cuerpo = doc.createParagraph();
            cuerpo.setSpacingAfter(240);
            XWPFRun rc = cuerpo.createRun();
            rc.setFontSize(11);
            rc.setFontFamily("Arial");
            String[] lineas = (sec.getContenido() == null ? "" : sec.getContenido()).split("\\R", -1);
            for (int i = 0; i < lineas.length; i++) {
                if (i > 0) {
                    rc.addBreak();
                }
                rc.setText(lineas[i]);
            }
        }
    }
}
