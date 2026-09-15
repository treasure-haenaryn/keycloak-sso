FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY extensions/phone-mfa-authenticator/pom.xml .
COPY extensions/phone-mfa-authenticator/src ./src
RUN mvn -q -DskipTests package

FROM quay.io/keycloak/keycloak:25.0
COPY --from=build /app/target/phone-mfa-authenticator.jar /opt/keycloak/providers/
