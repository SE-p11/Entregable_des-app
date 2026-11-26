package com.example.pdfbackend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Endpoint simple para comprobar que el backend (y LibreOffice) están listos. */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final LibreOfficeConverter conversor;

    public HealthController(LibreOfficeConverter conversor) {
        this.conversor = conversor;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("status", "ok");
        respuesta.put("libreoffice", conversor.isDisponible() ? "instalado" : "no encontrado (usa conversor interno)");
        respuesta.put("java", System.getProperty("java.version"));
        return respuesta;
    }
}
