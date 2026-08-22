# Single-stage image: the fat JAR must already be built (`mvn package`) before `docker build`.
# Keeping the build outside the image means the CI-built JAR and the locally-built JAR are the
# same artefact (SC-005).
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
