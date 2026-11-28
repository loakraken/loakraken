# syntax=docker/dockerfile:1.7
# LOAKraken 백엔드 운영 이미지. 테스트는 CI(ci.yml)에서 실행하고 여기서는 실행 가능한 jar만 만든다.
# 비밀값은 이미지에 넣지 않는다. 실행 시 /run/secrets(configtree)와 환경변수로 받는다(docs/ops/deploy.md).

FROM eclipse-temurin:25.0.5.1_2-jdk-noble AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon --quiet dependencies > /dev/null

COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon --quiet bootJar \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \; \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25.0.5.1_2-jre-noble
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app

# 자주 바뀌지 않는 층부터 복사해 이미지 층 재사용을 늘린다.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001
# 컨테이너 메모리 상한의 75%를 힙으로 쓰고, 메모리 부족 시 바로 종료해 compose가 재시작하게 한다.
# Base64 디코드 내장 최적화(intrinsic)를 끈다: CVE-2026-114938(x86-64 AVX-512 VBMI CPU에서 신뢰하지 않는 Base64 입력의 힙 쓰기). 고친 JDK 이미지로 올리면 뺀다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:-UseBASE64Intrinsics"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
