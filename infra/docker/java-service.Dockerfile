# syntax=docker/dockerfile:1.7
# Builds one Spring Boot service. The build context is the repository root.
#   docker build -f infra/docker/java-service.Dockerfile --build-arg SERVICE=ingestion-service --build-arg PORT=8081 .

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common-contracts/pom.xml common-contracts/
COPY ingestion-service/pom.xml ingestion-service/
COPY analysis-service/pom.xml analysis-service/
COPY reporting-service/pom.xml reporting-service/
COPY common-contracts/src common-contracts/src
ARG SERVICE
COPY ${SERVICE}/src ${SERVICE}/src
# Tests run in CI (mvn verify), not in the image build.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl ${SERVICE} -am package -Dmaven.test.skip=true \
    && java -Djarmode=tools -jar ${SERVICE}/target/${SERVICE}.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/data && chown app:app /app/data
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
ARG PORT
ENV PORT=${PORT}
EXPOSE ${PORT}
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- "http://localhost:${PORT}/actuator/health" > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
