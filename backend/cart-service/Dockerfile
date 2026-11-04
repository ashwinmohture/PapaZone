FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /app
COPY . .
RUN mvn -q -f cart-service/pom.xml -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/cart-service/target/*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]

