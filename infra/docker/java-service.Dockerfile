# syntax=docker/dockerfile:1.7
# Builds one Spring Boot service. The build context is the repository root.
#   docker build -f infra/docker/java-service.Dockerfile \
#     --build-arg SERVICE=ingestion-service --build-arg PORT=8081 .

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common-contracts/pom.xml common-contracts/
COPY ingestion-service/pom.xml ingestion-service/
COPY analysis-service/pom.xml analysis-service/
COPY reporting-service/pom.xml reporting-service/
COPY common-contracts/src/main common-contracts/src/main
ARG SERVICE
COPY ${SERVICE}/src/main ${SERVICE}/src/main
# Tests run in CI (mvn verify), not in the image build. Only src/main is copied, so nothing is compiled twice.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl ${SERVICE} -am package -DskipTests \
    && java -Djarmode=tools -jar ${SERVICE}/target/${SERVICE}.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
ARG APP_UID=10001
RUN addgroup -S -g ${APP_UID} app && adduser -S -u ${APP_UID} -G app app \
    && mkdir -p /app/data && chown app:app /app/data
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
ARG PORT
ENV PORT=${PORT} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE ${PORT}
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- "http://localhost:${PORT}/actuator/health" > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
