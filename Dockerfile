FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system teamflow && useradd --system --gid teamflow teamflow
COPY --from=build --chown=teamflow:teamflow /app/target/teamflow-0.1.0-SNAPSHOT.jar app.jar
USER teamflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
