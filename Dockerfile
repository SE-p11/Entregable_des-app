# ---- 1) Compilar con Maven + Java 17 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY src src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

# ---- 2) Imagen de ejecución con LibreOffice ----
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       libreoffice-writer \
       libreoffice-calc \
       fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/pdf-backend-0.0.1-SNAPSHOT.jar app.jar

ENV LIBREOFFICE_PATH=/usr/bin/soffice
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
