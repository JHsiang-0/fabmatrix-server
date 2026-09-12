# Farm Server Edition 镜像
# 构建前先执行：mvn clean package -DskipTests
FROM eclipse-temurin:25-jre

WORKDIR /app

ENV TZ=Asia/Shanghai

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system farm \
    && useradd --system --gid farm --home-dir /app --no-create-home farm \
    && mkdir -p /app/logs /app/uploads \
    && chown -R farm:farm /app

COPY --chown=farm:farm target/Farm-0.0.1-SNAPSHOT.jar /app/farm.jar

USER farm
EXPOSE 28081

ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-XX:+UseContainerSupport", "-jar", "/app/farm.jar"]
