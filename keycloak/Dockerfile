FROM maven:3.9-eclipse-temurin-21 AS build-phone-mfa
WORKDIR /app
COPY extensions/phone-mfa-authenticator/pom.xml .
COPY extensions/phone-mfa-authenticator/src ./src
RUN mvn -q -DskipTests package

FROM maven:3.9-eclipse-temurin-21 AS build-single-session
WORKDIR /app
COPY extensions/single-session-authenticator/pom.xml .
COPY extensions/single-session-authenticator/src ./src
RUN mvn -q -DskipTests package

FROM quay.io/keycloak/keycloak:25.0
COPY --from=build-phone-mfa /app/target/phone-mfa-authenticator.jar /opt/keycloak/providers/
COPY --from=build-single-session /app/target/single-session-authenticator.jar /opt/keycloak/providers/
