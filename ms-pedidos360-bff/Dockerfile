# Etapa 1: Compilación con Maven y Java 21
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/main ./src/main
RUN ./mvnw -B -ntp -Dmaven.test.skip=true package

# Etapa 2: Ejecución con Java 21
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
