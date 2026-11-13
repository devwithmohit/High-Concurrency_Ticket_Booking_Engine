# Multi-stage Dockerfile for Spring Boot 3.4+ / Java 21
# Stage 1: Build JAR using Maven
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml .
# Download dependencies in a separate layer for faster cached builds
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal Runtime Image optimized for 512MB RAM free-tier container
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Add non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser:appgroup

COPY --from=builder /build/target/*.jar app.jar

# JVM tuning flags for container memory optimization under 512MB RAM constraints
ENV JAVA_TOOL_OPTIONS="-Xmx320m -Xms200m -XX:MaxMetaspaceSize=96m -XX:+UseG1GC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar app.jar"]
