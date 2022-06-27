FROM ghcr.io/graalvm/graalvm-ce:java11-21.2.0

ENV VERTICLE_FILE transforming-js.jar

# Set the location of the verticles
ENV VERTICLE_HOME /usr/verticles

EXPOSE 8080

RUN mkdir -p $VERTICLE_HOME && groupadd vertx && useradd -g vertx vertx && chown -R vertx $VERTICLE_HOME && chmod -R g+w $VERTICLE_HOME

COPY target/$VERTICLE_FILE $VERTICLE_HOME/
COPY scripts $VERTICLE_HOME/scripts/

USER vertx

# Launch the verticle
WORKDIR $VERTICLE_HOME

ENTRYPOINT ["sh", "-c"]
CMD ["exec java $JAVA_OPTS -jar $VERTICLE_FILE"]
