package com.example.pdfbackend;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Llena la plantilla informe_afirmado.docx:
 *  - reemplaza los marcadores {{CLAVE}} por texto,
 *  - cambia las imágenes de portada, mapa y curva granulométrica,
 *  - inserta el anexo de laboratorio y el panel fotográfico (cantidad libre de fotos).
 *
 * No depende de Spring ni de Jackson: solo del JDK.
 */
public class InformeDocxBuilder {

    /** Una imagen subida por el usuario. */
    public record Foto(byte[] datos, String descripcion) {}

    private static final long EMU_POR_CM = 360_000L;
    private static final String REL_IMAGEN =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";

    private final Map<String, byte[]> partes = new LinkedHashMap<>();
    private String documentXml;
    private String relsXml;
    private String contentTypes;
    private int contadorFotos = 0;

    public byte[] generar(InputStream plantilla,
                          Map<String, String> valores,
                          Foto portada,
                          Foto mapa,
                          Foto curva,
                          List<Foto> anexoLab,
                          List<Foto> panel) throws IOException {

        cargarZip(plantilla);

        // 1) imágenes fijas de la plantilla (rId8 = portada, rId9 = mapa, rId10 = curva)
        reemplazarOQuitarImagen("rId8", portada);
        reemplazarOQuitarImagen("rId9", mapa);
        reemplazarOQuitarImagen("rId10", curva);
        if (mapa == null) {
            // sin mapa tampoco tiene sentido el globo "Cantera X"
            documentXml = documentXml.replaceAll(
                    "(?s)<mc:AlternateContent>(?:(?!</mc:AlternateContent>).)*?Bocadillo.*?</mc:AlternateContent>", "");
        }

        // 2) anexo y panel (hay que hacerlo ANTES de reemplazar textos)
        documentXml = documentXml.replace(
                "<w:p><w:r><w:t>{{ANEXO_LAB}}</w:t></w:r></w:p>",
                bloqueAnexo(anexoLab));
        documentXml = documentXml.replace(
                "<w:p><w:r><w:t>{{PANEL_FOTOGRAFICO}}</w:t></w:r></w:p>",
                bloquePanel(panel));

        // 3) bloques de texto de varias líneas: cada línea = un párrafo con el formato del original
        quitarFilasOpcionales(valores);
        expandirBloques(valores);

        // 4) textos
        Matcher m = Pattern.compile("\\{\\{([A-Z0-9_]+)}}").matcher(documentXml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = valores.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(aXml(v)));
        }
        m.appendTail(sb);
        documentXml = sb.toString();

        return empaquetar();
    }

    // ------------------------------------------------------------------ filas opcionales

    /**
     * Los marcadores {{OPC_...}} están dentro de una fila de tabla. Si el usuario dejó ese dato vacío,
     * la fila completa se quita (por ejemplo, la fila de CBR cuando no se hizo ese ensayo).
     */
    private void quitarFilasOpcionales(Map<String, String> valores) {
        Matcher m = Pattern.compile("\\{\\{(OPC_[A-Z0-9_]+)}}").matcher(documentXml);
        java.util.Set<String> vacios = new java.util.LinkedHashSet<>();
        while (m.find()) {
            if (valores.getOrDefault(m.group(1), "").isBlank()) vacios.add(m.group(1));
        }
        for (String clave : vacios) {
            String marcador = "{{" + clave + "}}";
            int idx;
            while ((idx = documentXml.indexOf(marcador)) >= 0) {
                int ini = Math.max(documentXml.lastIndexOf("<w:tr>", idx), documentXml.lastIndexOf("<w:tr ", idx));
                int fin = documentXml.indexOf("</w:tr>", idx) + "</w:tr>".length();
                documentXml = documentXml.substring(0, ini) + documentXml.substring(fin);
            }
        }
    }

    // ------------------------------------------------------------------ bloques de texto

    /**
     * Los marcadores {{TXT_...}} están solos en un párrafo de la plantilla. Aquí ese párrafo se copia
     * una vez por cada línea escrita por el usuario (las líneas vacías se ignoran). Así cada línea sale
     * como un párrafo real, con la misma justificación, viñeta y fuente que tenía el texto original.
     */
    private void expandirBloques(Map<String, String> valores) {
        Pattern marcador = Pattern.compile("\\{\\{(TXT_[A-Z0-9_]+)}}");
        while (true) {
            Matcher m = marcador.matcher(documentXml);
            if (!m.find()) return;

            int ini = Math.max(documentXml.lastIndexOf("<w:p>", m.start()),
                               documentXml.lastIndexOf("<w:p ", m.start()));
            int fin = documentXml.indexOf("</w:p>", m.end()) + "</w:p>".length();
            // los identificadores internos (paraId/textId) no pueden repetirse entre párrafos: se quitan
            String parrafo = documentXml.substring(ini, fin)
                    .replaceAll("\\s+w14:(?:paraId|textId)=\"[^\"]*\"", "");

            StringBuilder copias = new StringBuilder();
            for (String linea : valores.getOrDefault(m.group(1), "").split("\\R")) {
                if (linea.isBlank()) continue;
                // "{{" dentro del texto del usuario no debe volver a interpretarse como marcador
                String seguro = linea.trim().replace("{{", "{ {");
                copias.append(parrafo.replace(m.group(), aXml(seguro)));
            }
            documentXml = documentXml.substring(0, ini) + copias + documentXml.substring(fin);
        }
    }

    // ------------------------------------------------------------------ zip

    private void cargarZip(InputStream in) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                partes.put(e.getName(), zis.readAllBytes());
            }
        }
        documentXml = texto(partes.get("word/document.xml"));
        relsXml = texto(partes.get("word/_rels/document.xml.rels"));
        contentTypes = texto(partes.get("[Content_Types].xml"));
    }

    /** Quita las imágenes de ejemplo que ya no se usan para que el archivo no pese de más ni quede "corrupto". */
    private void limpiarImagenesSinUso() {
        Matcher m = Pattern.compile("<Relationship [^>]*/>").matcher(relsXml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rel = m.group();
            Matcher id = Pattern.compile("Id=\"([^\"]+)\"").matcher(rel);
            boolean esImagen = rel.contains("/relationships/image");
            if (esImagen && id.find() && !documentXml.contains("r:embed=\"" + id.group(1) + "\"")) {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        relsXml = sb.toString();

        java.util.Set<String> usados = new java.util.HashSet<>();
        Matcher t = Pattern.compile("Target=\"([^\"]+)\"").matcher(relsXml);
        while (t.find()) usados.add("word/" + t.group(1));
        for (Map.Entry<String, byte[]> e : partes.entrySet()) {
            if (e.getKey().endsWith(".rels") && !e.getKey().equals("word/_rels/document.xml.rels")) {
                Matcher o = Pattern.compile("Target=\"([^\"]+)\"").matcher(texto(e.getValue()));
                while (o.find()) usados.add("word/" + o.group(1));
            }
        }
        partes.keySet().removeIf(k -> k.startsWith("word/media/") && !usados.contains(k));
    }

    private byte[] empaquetar() throws IOException {
        limpiarImagenesSinUso();
        partes.put("word/document.xml", documentXml.getBytes(StandardCharsets.UTF_8));
        partes.put("word/_rels/document.xml.rels", relsXml.getBytes(StandardCharsets.UTF_8));
        partes.put("[Content_Types].xml", contentTypes.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            // [Content_Types].xml primero, como pide el formato
            escribir(zos, "[Content_Types].xml", partes.get("[Content_Types].xml"));
            for (Map.Entry<String, byte[]> e : partes.entrySet()) {
                if (!e.getKey().equals("[Content_Types].xml")) escribir(zos, e.getKey(), e.getValue());
            }
        }
        return bos.toByteArray();
    }

    private static void escribir(ZipOutputStream zos, String nombre, byte[] datos) throws IOException {
        zos.putNextEntry(new ZipEntry(nombre));
        zos.write(datos);
        zos.closeEntry();
    }

    // ------------------------------------------------------------- imágenes

    /** Cambia la imagen de la relación indicada; si el usuario no subió nada, quita la imagen de ejemplo. */
    private void reemplazarOQuitarImagen(String rId, Foto foto) throws IOException {
        Pattern p = Pattern.compile("(?s)<w:drawing>(?:(?!</w:drawing>).)*?r:embed=\"" + rId + "\".*?</w:drawing>");
        Matcher m = p.matcher(documentXml);
        if (!m.find()) return;
        String dibujo = m.group();

        if (foto == null || foto.datos() == null || foto.datos().length == 0) {
            documentXml = documentXml.substring(0, m.start()) + documentXml.substring(m.end());
            return;
        }

        Dimension d = dimensiones(foto.datos());
        String ext = extension(foto.datos());
        String nombre = "word/media/usuario_" + rId + "." + ext;
        partes.put(nombre, foto.datos());
        asegurarTipo(ext);
        relsXml = relsXml.replaceAll(
                "(<Relationship [^>]*Id=\"" + rId + "\"[^>]*Target=\")[^\"]*(\")",
                "$1media/usuario_" + rId + "." + ext + "$2");
        relsXml = relsXml.replaceAll(
                "(<Relationship [^>]*Target=\")[^\"]*(\"[^>]*Id=\"" + rId + "\")",
                "$1media/usuario_" + rId + "." + ext + "$2");

        // ajustar el tamaño al recuadro original, sin deformar la imagen
        Matcher ex = Pattern.compile("<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"").matcher(dibujo);
        if (ex.find()) {
            long maxW = Long.parseLong(ex.group(1));
            long maxH = Long.parseLong(ex.group(2));
            long[] wh = encajar(d, maxW, maxH);
            dibujo = dibujo.replaceAll("<wp:extent cx=\"\\d+\" cy=\"\\d+\"",
                    "<wp:extent cx=\"" + wh[0] + "\" cy=\"" + wh[1] + "\"");
            dibujo = dibujo.replaceAll("<a:ext cx=\"\\d+\" cy=\"\\d+\"",
                    "<a:ext cx=\"" + wh[0] + "\" cy=\"" + wh[1] + "\"");
            dibujo = dibujo.replaceAll("<a:srcRect[^>]*/>", "");
        }
        documentXml = documentXml.substring(0, m.start()) + dibujo + documentXml.substring(m.end());
    }

    private record Dimension(int w, int h) {}

    private static Dimension dimensiones(byte[] datos) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(datos));
        if (img == null) {
            throw new IllegalArgumentException("Formato de imagen no soportado. Usa JPG o PNG.");
        }
        return new Dimension(img.getWidth(), img.getHeight());
    }

    private static String extension(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        if (d.length > 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return "jpeg";
        throw new IllegalArgumentException("Formato de imagen no soportado. Usa JPG o PNG.");
    }

    private void asegurarTipo(String ext) {
        if (!contentTypes.contains("Extension=\"" + ext + "\"")) {
            String mime = ext.equals("png") ? "image/png" : "image/jpeg";
            contentTypes = contentTypes.replaceFirst("<Default ",
                    Matcher.quoteReplacement("<Default Extension=\"" + ext + "\" ContentType=\"" + mime + "\"/><Default "));
        }
    }

    /** Escala la imagen para que quepa en maxW x maxH (EMU) manteniendo la proporción. */
    private static long[] encajar(Dimension d, long maxW, long maxH) {
        double escala = Math.min((double) maxW / d.w(), (double) maxH / d.h());
        return new long[]{Math.round(d.w() * escala), Math.round(d.h() * escala)};
    }

    /** Registra una imagen nueva y devuelve el Id de la relación. */
    private String registrarFoto(Foto foto) throws IOException {
        contadorFotos++;
        String ext = extension(foto.datos());
        String rId = "rIdFoto" + contadorFotos;
        partes.put("word/media/foto_" + contadorFotos + "." + ext, foto.datos());
        asegurarTipo(ext);
        relsXml = relsXml.replace("</Relationships>",
                "<Relationship Id=\"" + rId + "\" Type=\"" + REL_IMAGEN + "\" Target=\"media/foto_"
                        + contadorFotos + "." + ext + "\"/></Relationships>");
        return rId;
    }

    // --------------------------------------------- anexo y panel fotográfico

    private static final String SALTO = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>";

    /** Cada imagen del anexo (hojas de ensayo) va en su propia página. */
    private String bloqueAnexo(List<Foto> fotos) throws IOException {
        if (fotos == null || fotos.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Foto f : fotos) {
            Dimension d = dimensiones(f.datos());
            long[] wh = encajar(d, 15 * EMU_POR_CM, 22 * EMU_POR_CM);
            sb.append(SALTO).append(parrafoImagen(registrarFoto(f), wh, 0));
        }
        return sb.toString();
    }

    /** Dos fotos por página, cada una con su descripción debajo. */
    private String bloquePanel(List<Foto> fotos) throws IOException {
        if (fotos == null || fotos.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fotos.size(); i++) {
            Foto f = fotos.get(i);
            Dimension d = dimensiones(f.datos());
            long[] wh = encajar(d, 12 * EMU_POR_CM, 8 * EMU_POR_CM);
            sb.append(parrafoImagen(registrarFoto(f), wh, 240));
            sb.append(parrafoDescripcion(f.descripcion()));
            boolean finDePagina = (i % 2 == 1) && i < fotos.size() - 1;
            if (finDePagina) sb.append(SALTO);
        }
        return sb.toString();
    }

    private String parrafoImagen(String rId, long[] wh, int antes) {
        int id = 1000 + contadorFotos;
        return "<w:p><w:pPr><w:keepNext/><w:spacing w:before=\"" + antes + "\" w:after=\"60\"/><w:jc w:val=\"center\"/></w:pPr>"
                + "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + wh[0] + "\" cy=\"" + wh[1] + "\"/>"
                + "<wp:docPr id=\"" + id + "\" name=\"Foto " + contadorFotos + "\"/>"
                + "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>"
                + "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"foto_" + contadorFotos + "\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"" + rId + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + wh[0] + "\" cy=\"" + wh[1] + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
    }

    private String parrafoDescripcion(String texto) {
        String t = texto == null ? "" : texto.trim();
        return "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"240\"/><w:jc w:val=\"center\"/></w:pPr>"
                + "<w:r><w:rPr><w:rFonts w:ascii=\"Cambria\" w:hAnsi=\"Cambria\"/><w:sz w:val=\"20\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + aXml(t) + "</w:t></w:r></w:p>";
    }

    // ---------------------------------------------------------------- util

    private static String texto(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Escapa XML y convierte saltos de línea en saltos de línea de Word. */
    private static String aXml(String s) {
        if (s == null) return "";
        String e = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return e.replace("\r\n", "\n").replace("\n", "</w:t><w:br/><w:t xml:space=\"preserve\">");
    }
}
