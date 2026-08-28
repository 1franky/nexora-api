# Misma imagen para dev local (compose.yaml) y para el despliegue en el VPS;
# lo que cambia entre ambos es el .env (ver .env.example / README.md).

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

EXPOSE 3005
ENTRYPOINT ["java", "-jar", "app.jar"]
