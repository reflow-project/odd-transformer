package io.piveau.transforming;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testing the transformer")
@ExtendWith(VertxExtension.class)
class TransformingTest {

    @BeforeEach
    void startImporter(Vertx vertx, VertxTestContext testContext) {
        vertx.deployVerticle(new MainVerticle(), testContext.succeeding(response -> testContext.verify(testContext::completeNow)));
    }

//    @Test
    @DisplayName("Receiving pipe and forward")
    void sendDataset(Vertx vertx, VertxTestContext testContext) {
        Checkpoint checkpoint = testContext.checkpoint(2);

        // Mockup hub
        vertx.createHttpServer().requestHandler(request -> {
            testContext.verify(() -> {
                assertEquals("application/json", request.getHeader("Content-Type"));
            });
            request.response().setStatusCode(202).end(ar -> {
                if(ar.succeeded()) {
                    checkpoint.flag();
                } else {
                    testContext.failNow(ar.cause());
                }
            });
        }).listen(8098);

        // Injecting pipe
        sendPipe("test-pipe.json", vertx, testContext, checkpoint);
    }

    private void sendPipe(String pipeFile, Vertx vertx, VertxTestContext testContext, Checkpoint checkpoint) {
        vertx.fileSystem().readFile(pipeFile, result -> {
            if (result.succeeded()) {
                JsonObject pipe = new JsonObject(result.result());
                WebClient client = WebClient.create(vertx);
                client.post(8080, "localhost", "/pipe")
                        .putHeader("Content-Type", "application/json")
                        .sendJsonObject(pipe, testContext.succeeding(response -> testContext.verify(() -> {
                            if (response.statusCode() == 202) {
                                checkpoint.flag();
                            } else {
                                testContext.failNow(new Throwable(response.statusMessage()));
                            }
                        })));
            } else {
                testContext.failNow(result.cause());
            }
        });
    }

}
