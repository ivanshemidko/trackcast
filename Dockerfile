FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp verify

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --gid 10001 trackcast && useradd --uid 10001 --gid trackcast --no-create-home trackcast \
    && mkdir /data && chown trackcast:trackcast /data
WORKDIR /app
COPY --from=build /build/target/trackcast-1.0-SNAPSHOT.jar /app/trackcast.jar
ENV DATA_DIR=/data
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/trackcast.jar"]
