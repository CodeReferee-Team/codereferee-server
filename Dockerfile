# syntax=docker/dockerfile:1

# 빌드는 반드시 래퍼로 한다. CI의 ./gradlew test 와 같은 Gradle 을 써야
# "CI는 통과했는데 이미지 빌드는 깨진다"가 생기지 않는다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src

# 캐시 마운트는 레이어에 남지 않으므로 로컬 재빌드만 빨라진다.
# CI는 매번 의존성을 받는다. 느려지면 러너에서 jar 를 만들고
# 이미지는 jar 만 복사하는 쪽으로 바꾼다.
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
