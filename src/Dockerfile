FROM maven:3.9.11-eclipse-temurin-25-alpine AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q clean package -DskipTests \
    && mv target/imadjOnline-*.jar target/app.jar

FROM eclipse-temurin:25-jre-alpine AS run
WORKDIR /app

RUN apk add --no-cache unzip

COPY --from=build /app/target/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
