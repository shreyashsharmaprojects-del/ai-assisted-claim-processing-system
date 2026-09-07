# Production backend image: builds the Spring Boot jar with Maven, then runs it on a
# minimal JRE. No source, no build tools, no secrets in the final layer.
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S claims && adduser -S claims -G claims
WORKDIR /app
COPY --from=build /src/target/claims-backend-*.jar app.jar
RUN mkdir -p /var/lib/claims-uploads && chown -R claims:claims /app /var/lib/claims-uploads
VOLUME /var/lib/claims-uploads
USER claims
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
