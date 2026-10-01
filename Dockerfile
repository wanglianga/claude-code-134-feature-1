# ---- 构建阶段：Maven + JDK17 编译打包 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷贝 pom 预下载依赖，利用构建缓存
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- 运行阶段：仅 JRE ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/training-crm-1.0.0.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
