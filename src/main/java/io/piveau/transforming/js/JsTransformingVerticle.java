package io.piveau.transforming.js;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.piveau.pipe.PipeContext;
import io.piveau.transforming.repositories.GitRepository;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.*;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;

public class JsTransformingVerticle extends AbstractVerticle {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String ADDRESS = "io.piveau.pipe.transformation.js.queue";

    private static final String ENV_PIVEAU_REPOSITORY_DEFAULT_BRANCH = "PIVEAU_REPOSITORY_DEFAULT_BRANCH";

    private Cache<String, ScriptEngine> cache;

    private String defaultBranch;

    @Override
    public void start(Promise<Void> startPromise) {
        vertx.eventBus().consumer(ADDRESS, this::handlePipe);

        CacheManager cacheManager = CacheManagerBuilder.newCacheManagerBuilder()
                .withCache("transformer", CacheConfigurationBuilder.newCacheConfigurationBuilder(String.class, ScriptEngine.class,
                        ResourcePoolsBuilder.newResourcePoolsBuilder().heap(50, EntryUnit.ENTRIES))
                .withExpiry(ExpiryPolicyBuilder.timeToIdleExpiration(Duration.ofHours(12))))
                .build(true);

        cache = cacheManager.getCache("transformer", String.class, ScriptEngine.class);

        ConfigStoreOptions envStoreOptions = new ConfigStoreOptions()
                .setType("env")
                .setConfig(new JsonObject().put("keys", new JsonArray().add(ENV_PIVEAU_REPOSITORY_DEFAULT_BRANCH)));
        ConfigRetriever retriever = ConfigRetriever.create(vertx, new ConfigRetrieverOptions().addStore(envStoreOptions));
        retriever.getConfig(ar -> {
            if (ar.succeeded()) {
                defaultBranch = ar.result().getString(ENV_PIVEAU_REPOSITORY_DEFAULT_BRANCH, "master");
                startPromise.complete();
            } else {
                startPromise.fail(ar.cause());
            }
        });
        retriever.listen(change -> defaultBranch = change.getNewConfiguration().getString(ENV_PIVEAU_REPOSITORY_DEFAULT_BRANCH, "master"));
    }

    private void handlePipe(Message<PipeContext> message) {
        PipeContext pipeContext = message.body();
        pipeContext.log().trace("Incoming pipe");
        JsonObject config = pipeContext.getConfig();

        JsonObject dataInfo = pipeContext.getDataInfo();
        if (!dataInfo.containsKey("content") || dataInfo.getString("content").equals("metadata")) {

            String runId = pipeContext.getPipe().getHeader().getRunId();
            ScriptEngine engine = cache.get(runId);
            if (engine == null) {
                String script;
                switch (config.getString("scriptType")) {
                    case "repository":
                        JsonObject repository = config.getJsonObject("repository");
                        String uri = repository.getString("uri");
                        String branch = repository.getString("branch", defaultBranch);
                        String username = repository.getString("username");
                        String token = repository.getString("token");
                        GitRepository gitRepo = GitRepository.open(uri, username, token, branch);
                        Path file = gitRepo.resolve(repository.getString("script"));
                        script = vertx.fileSystem().readFileBlocking(file.toString()).toString();
                        break;
                    case "localFile":
                        String path = config.getString("path");
                        if (vertx.fileSystem().existsBlocking("scripts/" + path)) {
                            script = vertx.fileSystem().readFileBlocking("scripts/" + path).toString();
                        } else {
                            log.error("Script {} not found", path);
                            pipeContext.log().error("Script {} not found", path);
                            return;
                        }
                        break;
                    case "embedded":
                    default:
                        script = config.getString("script");
                }

                try {
//                    engine = new GraalJSEngineFactory().getScriptEngine();
                    engine = new ScriptEngineManager().getEngineByName("graal.js");
                    ScriptContext context = engine.getContext();
                    context.setAttribute("name", "graal.js", ScriptContext.ENGINE_SCOPE);

                    engine.eval(script);
                    engine.eval("function executeTransformation(obj) { return JSON.stringify(transforming(JSON.parse(obj))) }");

                    if (config.containsKey("params")) {
                        engine.eval("var params = " + config.getJsonObject("params").encode() + ";");
                    }

                    if (config.getBoolean("single")) {
                        cache.put(runId, engine);
                    }
                } catch (ScriptException e) {
                    log.error("Initializing script template", e);
                    pipeContext.log().error("Initialize script", e);
                }
            }

            JsonObject info = pipeContext.getDataInfo();
            Invocable jsInvoke = (Invocable) engine;

            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true);
                mapper.configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true);
                mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

                JsonNode input = mapper.readTree(pipeContext.getStringData());
                pipeContext.log().debug("Transformation input:\n{}", input.toPrettyString());

                Object output = jsInvoke.invokeFunction("executeTransformation", input.toString());

                String out = output.toString();
                pipeContext.log().debug("Transformation result:\n{}", out);

                String outputFormat = config.getString("outputFormat", "application/ld+json");
                pipeContext.setResult(out, outputFormat, info).forward();
                pipeContext.log().info("Data transformed: {}", info);

            } catch (IOException | NoSuchMethodException | ScriptException e) {
                log.error("transforming data", e);
                pipeContext.log().error(info.toString(), e);
            }
        } else {
            pipeContext.log().trace("Passing pipe");
            pipeContext.pass();
        }
    }
}
