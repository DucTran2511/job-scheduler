FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy parent pom and install it
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Copy common module and build it
COPY common/pom.xml common/
COPY common/src common/src
RUN ./mvnw install -pl common -am -DskipTests

# Copy api-service and build it
COPY api-service/pom.xml api-service/
COPY api-service/src api-service/src
RUN ./mvnw package -pl api-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/api-service/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

