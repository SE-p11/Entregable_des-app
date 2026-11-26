# pdf-backend

Backend Spring Boot (Java 17) que genera informes PDF/DOCX.

## Generación del PDF

El backend convierte el DOCX a PDF con **LibreOffice** (mantiene el diseño de la plantilla).
Si LibreOffice no está disponible, usa automáticamente un **conversor interno** con
Apache POI + PDFBox: el PDF se genera igual, con un formato más básico.

Comprueba el estado con:

```
GET http://localhost:8080/api/health
```

## Ejecutar en Windows (local)

1. Instalar **JDK 17** o superior.
2. Instalar **LibreOffice** (https://www.libreoffice.org/) — recomendado para el diseño completo.
3. En la raíz del proyecto:

```
.\mvnw.cmd spring-boot:run
```

El backend queda en `http://localhost:8080`.

Si LibreOffice está en una ruta no habitual, edita `src/main/resources/application.properties`:

```
libreoffice.path=C:/Program Files/LibreOffice/program/soffice.exe
```

Para forzar el conversor interno (sin LibreOffice):

```
libreoffice.enabled=false
```

## Ejecutar con Docker (recomendado para servidor)

El `Dockerfile` ya incluye Java 17 y LibreOffice, así que no hay que instalar nada en el servidor:

```
docker build -t pdf-backend .
docker run -d --name pdf-backend -p 8080:8080 pdf-backend
```

Comprobar: `curl http://localhost:8080/api/health`

## Endpoints

| Método | Ruta | Descripción |
| --- | --- | --- |
| `POST` | `/api/informe/generar` | Genera el informe de afirmado (PDF por defecto, `formato=docx` para Word). |
| `POST` | `/api/pdf/merge` | Combina informe y archivos adjuntos en un solo PDF. |
| `GET` | `/api/health` | Estado del servicio y de LibreOffice. |

Ejemplo mínimo:

```
curl -F "formato=pdf" -F "datos=titulo=INFORME&nombreProyecto=Cantera Prueba&fecha=2026-05-20" \
  http://localhost:8080/api/informe/generar -o informe.pdf
```

CORS permitido para `http://localhost:4200` (frontend Angular).
