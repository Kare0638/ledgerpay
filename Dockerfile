# Builds any service module: docker build --build-arg MODULE=payment-service .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/
COPY mock-psp/pom.xml mock-psp/
COPY acceptance-tests/pom.xml acceptance-tests/
COPY benchmarks/pom.xml benchmarks/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${MODULE} -am dependency:go-offline
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${MODULE} -am package -DskipTests -Dspotless.check.skip=true \
    && cp ${MODULE}/target/${MODULE}-*-exec.jar /app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 app
USER app
COPY --from=build /app.jar /app/app.jar
# With one or two CPUs the common pool's parallelism drops below 2, and the JDK HTTP client then
# starts a platform thread per request (ADR 0008). Small containers are exactly that case.
ENTRYPOINT ["java", "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2", "-jar", "/app/app.jar"]
