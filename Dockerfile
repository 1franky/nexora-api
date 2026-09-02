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
# user.timezone fijo a America/Mexico_City: sin esto la JVM corre en UTC (el
# default de la imagen base), y todo el código que usa LocalDate.now()/
# YearMonth.now() sin zona explícita (dashboard, tarjetas, notificaciones)
# calcula "hoy"/"este mes" ~6h adelantado respecto al usuario. Eso hacía que,
# entre las 6pm y medianoche hora CDMX, el último día del mes ya se calculara
# como el primero del siguiente (ver README.md).
ENTRYPOINT ["java", "-Duser.timezone=America/Mexico_City", "-jar", "app.jar"]
