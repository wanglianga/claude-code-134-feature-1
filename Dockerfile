# 多阶段构建：Maven 编译打包 → JRE 17 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/training-crm-1.0.0.jar app.jar
EXPOSE 8080
# REDIS_HOST/REDIS_PORT 由 compose 注入
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
