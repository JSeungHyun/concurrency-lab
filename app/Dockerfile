FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
# 의존성 레이어를 소스와 분리해 소스만 바뀌면 다시 받지 않게 한다
RUN gradle dependencies --no-daemon > /dev/null 2>&1 || true
COPY src src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/app.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
