FROM maven:3.9.11-eclipse-temurin-25 AS build

WORKDIR /app

COPY . .

RUN mvn -B -ntp -U -f discovery-server/pom.xml -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /app/discovery-server/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]