# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S mormi && adduser -S -G mormi mormi

WORKDIR /app

COPY --chown=mormi:mormi build/libs/app.jar /app/app.jar

USER mormi

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
