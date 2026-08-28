# Imagen para despliegue en el VPS. El desarrollo local se hace fuera de
# Docker (ver README.md / plan.md); esta imagen solo se usa para producción.

# --- Etapa de build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# --- Etapa de ejecución ---
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN addgroup --system nexora && adduser --system --ingroup nexora nexora
COPY --from=build /workspace/build/libs/*.jar app.jar
USER nexora

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
