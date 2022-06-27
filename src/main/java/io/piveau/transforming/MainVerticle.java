package io.piveau.transforming;

import io.piveau.pipe.connector.PipeConnector;
import io.piveau.transforming.js.JsTransformingVerticle;
import io.vertx.core.*;

import java.util.Arrays;

public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Promise<Void> startPromise) {
        vertx.deployVerticle(JsTransformingVerticle.class, new DeploymentOptions().setWorker(true).setInstances(10))
                .compose(id -> PipeConnector.create(vertx))
                .onSuccess(connector -> {
                    connector.publishTo(JsTransformingVerticle.ADDRESS);
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    public static void main(String[] args) {
        String[] params = Arrays.copyOf(args, args.length + 1);
        params[params.length - 1] = MainVerticle.class.getName();
        Launcher.executeCommand("run", params);
    }

}
