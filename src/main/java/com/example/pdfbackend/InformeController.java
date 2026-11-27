package com.example.pdfbackend;

import com.example.pdfbackend.InformeDocxBuilder.Foto;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * POST /api/informe/generar  (multipart/form-data)
 *
 * Campos de texto (todos opcionales salvo los que quieras exigir):
 *   titulo, dosificacion, fecha, obra, tipoProyecto, nombreProyecto, solicita, realizadoPor,
 *   lugar, distrito, provincia, departamento,
 *   cantDepartamento, cantProvincia, cantDistrito, cantLugar, puntoReferencia, este, norte,
 *   muestra, grava, arena, finos, humedad, d10, d30, d60, cu, cc,
 *   aashto, sucs, nombreGrupo, gradacion, zaranda, ll, lp, ip, ig, mds, och, conclusion,
 *   formato = "pdf" | "docx"
 * Archivos: portada, mapa, curva (1 imagen cada uno), anexo (varias), panel (varias) + panelDescripcion (una por foto)
 */
@RestController
@RequestMapping("/api/informe")
@CrossOrigin(origins = "http://localhost:4200")
public class InformeController {

    private static final Locale ES = Locale.forLanguageTag("es-PE");
    /** Modelo "material": control de calidad de material para afirmado (cantera, pista, carretera...). */
    private static final String PLANTILLA_MATERIAL = "plantillas/informe_afirmado_completo.docx";
    /** Modelo "cimentacion": estudio de mecánica de suelos con fines de cimentación (casa, puente...). */
    private static final String PLANTILLA_EMS = "plantillas/informe_cimentacion.docx";

    private final LibreOfficeConverter conversor;

    public InformeController(LibreOfficeConverter conversor) {
        this.conversor = conversor;
    }

    @PostMapping(value = "/generar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> generar(
            @RequestParam Map<String, String> camposRecibidos,
            @RequestParam(value = "datos", required = false) String datos,
            @RequestParam(value = "portada", required = false) MultipartFile portada,
            @RequestParam(value = "mapa", required = false) MultipartFile mapa,
            @RequestParam(value = "curva", required = false) MultipartFile curva,
            @RequestParam(value = "anexo", required = false) List<MultipartFile> anexo,
            @RequestParam(value = "panel", required = false) List<MultipartFile> panel,
            @RequestParam(value = "panelDescripcion", required = false) List<String> panelDescripcion
    ) throws IOException {

        // Los textos llegan en un solo campo "datos" (formato a=1&b=2, codificado en UTF-8).
        // Así no se supera el máximo de partes de un multipart (Tomcat acepta 50 por defecto).
        Map<String, String> campos = new HashMap<>(camposRecibidos);
        if (datos != null && !datos.isBlank()) {
            campos.putAll(decodificar(datos));
        }
        Map<String, String> valores = valores(campos);

        List<Foto> fotosPanel = new ArrayList<>();
        if (panel != null) {
            for (int i = 0; i < panel.size(); i++) {
                if (panel.get(i).isEmpty()) continue;
                String desc = campos.containsKey("panelDesc" + i) ? campos.get("panelDesc" + i)
                        : (panelDescripcion != null && i < panelDescripcion.size()) ? panelDescripcion.get(i) : "";
                fotosPanel.add(new Foto(panel.get(i).getBytes(), desc));
            }
        }
        List<Foto> fotosAnexo = new ArrayList<>();
        if (anexo != null) {
            for (MultipartFile f : anexo) {
                if (!f.isEmpty()) fotosAnexo.add(new Foto(f.getBytes(), ""));
            }
        }

        byte[] docx;
        try (InputStream plantilla = new ClassPathResource("cimentacion".equalsIgnoreCase(campos.get("modelo")) ? PLANTILLA_EMS : PLANTILLA_MATERIAL).getInputStream()) {
            docx = new InformeDocxBuilder().generar(plantilla, valores,
                    foto(portada), foto(mapa), foto(curva), fotosAnexo, fotosPanel);
        }

        String nombre = "Informe_" + valores.getOrDefault("CANTERA", "afirmado").replaceAll("[^A-Za-z0-9_-]+", "_");
        boolean pdf = !"docx".equalsIgnoreCase(campos.getOrDefault("formato", "pdf"));
        byte[] salida = pdf ? convertirAPdf(docx) : docx;

        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nombre + (pdf ? ".pdf" : ".docx")).build().toString())
                .body(salida);
    }

    /** Falta plantilla, LibreOffice no está instalado, etc. => 500 con mensaje corto y legible. */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> falloInterno(IOException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.length() > 280) msg = msg.substring(0, 280);
        return ResponseEntity.internalServerError().contentType(MediaType.TEXT_PLAIN).body(msg);
    }

    /** Imagen inválida (por ejemplo WEBP) => 400 con mensaje legible. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> imagenInvalida(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(e.getMessage());
    }

    private static Map<String, String> decodificar(String datos) {
        Map<String, String> m = new HashMap<>();
        for (String par : datos.split("&")) {
            if (par.isEmpty()) continue;
            int i = par.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? par : par.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    // ------------------------------------------------------------ valores

    private Map<String, String> valores(Map<String, String> p) {
        Map<String, String> v = new HashMap<>();
        String cantera = mayus(p.get("nombreProyecto"));

        // portada
        v.put("TITULO", mayus(p.get("titulo")));
        v.put("DOSIFICACION", limpio(p.get("dosificacion")));
        v.put("OBRA", mayus(sinComillas(p.get("obra"))));
        v.put("TIPO_PROYECTO", mayus(p.get("tipoProyecto")));
        v.put("TIPO_CAP", capitalizar(p.get("tipoProyecto")));
        v.put("CANTERA", cantera);
        v.put("SOLICITA", mayus(p.get("solicita")));
        v.put("LUGAR", mayus(p.get("lugar")));
        v.put("DISTRITO", mayus(p.get("distrito")));
        v.put("PROVINCIA", mayus(p.get("provincia")));
        v.put("DEPARTAMENTO", mayus(p.get("departamento")));
        v.put("CIUDAD", capitalizar(p.get("departamento")));
        v.put("FECHA", fechaTexto(p.get("fecha")));

        // ubicación de la cantera
        v.put("CANT_DEPARTAMENTO", limpio(p.get("cantDepartamento")));
        v.put("CANT_PROVINCIA", limpio(p.get("cantProvincia")));
        v.put("CANT_DISTRITO", limpio(p.get("cantDistrito")));
        v.put("CANT_LUGAR", limpio(p.get("cantLugar")));
        String punto = limpio(p.get("puntoReferencia"));
        v.put("PUNTO_REFERENCIA", punto.isEmpty() ? v.get("TIPO_CAP") + " " + cantera : punto);
        v.put("ESTE", limpio(p.get("este")));
        v.put("NORTE", limpio(p.get("norte")));

        // resultados opcionales: si quedan vacíos, la fila completa desaparece de la tabla del informe
        for (String[] par : new String[][]{
                {"OPC_CBR", "cbr"}, {"OPC_ABRASION", "abrasion"},
                {"OPC_DF", "df"}, {"OPC_ANCHO_B", "anchoB"}, {"OPC_GAMMA", "gamma"}, {"OPC_COHESION", "cohesion"},
                {"OPC_PHI", "phi"}, {"OPC_QADM", "qadm"}, {"OPC_ASENTAMIENTO", "asentamiento"},
                {"OPC_NIVEL_FREATICO", "nivelFreatico"}, {"OPC_METODO", "metodoCapacidad"}}) {
            v.put(par[0], limpio(p.get(par[1])));
        }

        // ensayos
        for (String[] par : new String[][]{
                {"MUESTRA", "muestra"}, {"GRAVA", "grava"}, {"ARENA", "arena"}, {"FINOS", "finos"},
                {"HUMEDAD", "humedad"}, {"D10", "d10"}, {"D30", "d30"}, {"D60", "d60"}, {"CU", "cu"}, {"CC", "cc"},
                {"AASHTO", "aashto"}, {"SUCS", "sucs"}, {"NOMBRE_GRUPO", "nombreGrupo"},
                {"GRADACION", "gradacion"}, {"ZARANDA", "zaranda"},
                {"LL", "ll"}, {"LP", "lp"}, {"IP", "ip"}, {"IG", "ig"}, {"MDS", "mds"}, {"OCH", "och"}}) {
            v.put(par[0], limpio(p.get(par[1])));
        }
        v.put("SUCS", v.get("SUCS").toUpperCase(ES));
        v.put("NOMBRE_GRUPO", v.get("NOMBRE_GRUPO").toUpperCase(ES));

        // ---- títulos de sección (se usan igual en el índice y en el cuerpo) ----
        for (String[] par : new String[][]{
                {"T_COMENTARIOS", "tComentarios"}, {"T_ENSAYOS", "tEnsayos"}, {"T_UBICACION", "tUbicacion"},
                {"T_GRANULOMETRIA", "tGranulometria"}, {"T_LIMITES", "tLimites"}, {"T_PROCTOR", "tProctor"},
                {"T_MANIPULEO", "tManipuleo"}, {"T_CONCLUSIONES", "tConclusiones"}, {"ENCABEZADO", "encabezado"},
                {"T_PERFIL", "tPerfil"}, {"T_CAPACIDAD", "tCapacidad"}}) {
            v.put(par[0], mayus(p.get(par[1])));
        }

        // ---- bloques de texto: una línea = un párrafo. Las claves {cantera}, {dosificacion}... se reemplazan ----
        Map<String, String> claves = new HashMap<>();
        claves.put("{cantera}", dato(cantera));
        claves.put("{nombre}", dato(cantera));
        claves.put("{tipo}", dato(v.get("TIPO_CAP")));
        for (String[] par : new String[][]{
                {"{dosificacion}", "DOSIFICACION"}, {"{zaranda}", "ZARANDA"}, {"{gradacion}", "GRADACION"},
                {"{aashto}", "AASHTO"}, {"{sucs}", "SUCS"}, {"{nombreGrupo}", "NOMBRE_GRUPO"}, {"{ig}", "IG"},
                {"{muestra}", "MUESTRA"}, {"{grava}", "GRAVA"}, {"{arena}", "ARENA"}, {"{finos}", "FINOS"},
                {"{humedad}", "HUMEDAD"}, {"{cu}", "CU"}, {"{cc}", "CC"},
                {"{ll}", "LL"}, {"{lp}", "LP"}, {"{ip}", "IP"}, {"{mds}", "MDS"}, {"{och}", "OCH"},
                {"{cbr}", "OPC_CBR"}, {"{abrasion}", "OPC_ABRASION"},
                {"{df}", "OPC_DF"}, {"{b}", "OPC_ANCHO_B"}, {"{gamma}", "OPC_GAMMA"}, {"{c}", "OPC_COHESION"},
                {"{phi}", "OPC_PHI"}, {"{qadm}", "OPC_QADM"}, {"{asentamiento}", "OPC_ASENTAMIENTO"},
                {"{nivelFreatico}", "OPC_NIVEL_FREATICO"}, {"{metodo}", "OPC_METODO"}}) {
            claves.put(par[0], dato(v.get(par[1])));
        }
        claves.put("{profCalicata}", dato(limpio(p.get("profCalicata"))));
        for (String[] par : new String[][]{
                {"TXT_COMENTARIOS", "txtComentarios"}, {"TXT_ENSAYOS_INTRO", "txtEnsayosIntro"},
                {"TXT_ENSAYOS_NORMAS", "txtEnsayosNormas"}, {"TXT_GRANULOMETRIA", "txtGranulometria"},
                {"TXT_CLASIFICACION", "txtClasificacion"}, {"TXT_GRADACION", "txtGradacion"},
                {"TXT_CURVA", "txtCurva"}, {"TXT_LIMITES", "txtLimites"}, {"TXT_INDICE_GRUPO", "txtIndiceGrupo"},
                {"TXT_PROCTOR", "txtProctor"}, {"TXT_MANIPULEO", "txtManipuleo"},
                {"TXT_CONCLUSIONES_INTRO", "txtConclusionesIntro"}, {"TXT_PERFIL", "txtPerfil"}, {"TXT_CAPACIDAD", "txtCapacidad"},
                {"TXT_CONCLUSION", "conclusion"},
                {"TXT_RECOMENDACION", "recomendacion"}}) {
            String texto = limpio(p.get(par[1]));
            for (Map.Entry<String, String> c : claves.entrySet()) {
                texto = texto.replace(c.getKey(), c.getValue());
            }
            v.put(par[0], texto);
        }
        return v;
    }

    private static Foto foto(MultipartFile f) throws IOException {
        return (f == null || f.isEmpty()) ? null : new Foto(f.getBytes(), null);
    }

    /** Dato que falta => "—", para que en el informe se note que hay algo por completar. */
    private static String dato(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String limpio(String s) {
        return s == null ? "" : s.trim();
    }

    private static String mayus(String s) {
        return limpio(s).toUpperCase(ES);
    }

    private static String capitalizar(String s) {
        String t = limpio(s).toLowerCase(ES);
        return t.isEmpty() ? "" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** La plantilla ya trae las comillas alrededor de la obra. */
    private static String sinComillas(String s) {
        return limpio(s).replaceAll("^[\"“”]+|[\"“”]+$", "").trim();
    }

    /** "2026-05-20" -> "Mayo del 2026" */
    private static String fechaTexto(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            LocalDate d = LocalDate.parse(iso.trim());
            String mes = d.getMonth().getDisplayName(TextStyle.FULL, ES);
            return Character.toUpperCase(mes.charAt(0)) + mes.substring(1) + " del " + d.getYear();
        } catch (Exception e) {
            return iso;
        }
    }

    // ---------------------------------------------------------------- PDF

    private byte[] convertirAPdf(byte[] docx) throws IOException {
        try {
            return conversor.toPdf(docx, "docx");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversión interrumpida", e);
        }
    }
}
