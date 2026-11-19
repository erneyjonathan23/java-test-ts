# Built by CI from an already-tested jar (./mvnw package). Small JRE image, non-root user.
# Same JDK patch level as the one the tests run on; Dependabot keeps it current.
FROM eclipse-temurin:24-jre-alpine-3.22

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/payments-intake-*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
