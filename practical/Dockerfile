# Built by CI from an already-tested jar (./mvnw package). Small JRE image, non-root user.
FROM eclipse-temurin:21.0.5_11-jre-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/payments-intake-*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
