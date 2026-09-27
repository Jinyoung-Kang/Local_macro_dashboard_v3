# 백엔드 이미지 (Java 21 + Spring Boot)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 의존성 레이어를 먼저 캐시합니다(소스만 바뀌면 재다운로드하지 않습니다).
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# wget은 헬스체크에 씁니다.
# root로 돌지 않습니다. 쓰는 곳은 /tmp(내장 톰캣 작업 폴더)뿐이고 app.jar는 읽기만 합니다.
RUN apk add --no-cache wget tzdata \
    && addgroup -S app && adduser -S -G app -u 10001 app
ENV TZ=Asia/Seoul

COPY --from=build /build/target/*.jar app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
