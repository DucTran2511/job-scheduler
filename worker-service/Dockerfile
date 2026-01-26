FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy parent pom and install it
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Copy ALL module poms to satisfy reactor
COPY common/pom.xml common/
COPY api-service/pom.xml api-service/
COPY worker-service/pom.xml worker-service/

# Copy common source and build it
COPY common/src common/src
RUN ./mvnw install -pl common -am -DskipTests

# Copy worker-service and build it
COPY worker-service/pom.xml worker-service/
COPY worker-service/src worker-service/src
RUN ./mvnw package -pl worker-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache bash
COPY --from=builder /app/worker-service/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
