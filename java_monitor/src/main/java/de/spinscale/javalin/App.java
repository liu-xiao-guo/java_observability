package de.spinscale.javalin;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.plugin.metrics.MicrometerPlugin;
import io.javalin.core.security.BasicAuthCredentials;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import co.elastic.apm.attach.ElasticApmAttacher;

import co.elastic.apm.api.ElasticApm;
import org.apache.http.client.fluent.Request;

public class App {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        ElasticApmAttacher.attach();
        Javalin app = Javalin.create(config -> {
            config.requestLogger((ctx, executionTimeMs) -> {
                String userAgent = ctx.userAgent() != null ? ctx.userAgent() : "-";
                logger.info("{} {} {} {} \"{}\" {}",
                        ctx.method(), ctx.req.getPathInfo(), ctx.res.getStatus(),
                        ctx.req.getRemoteHost(), userAgent, executionTimeMs.longValue());
            });

            config.registerPlugin(new MicrometerPlugin());
        });

        // app.before(ctx -> ElasticApm.currentTransaction()
        // .setName(ctx.method() + " " + ctx.path()));

        app.after(ctx -> ElasticApm.currentTransaction().setName(ctx.method()
            + " " + ctx.endpointHandlerPath()));

        app.get("/exception", ctx -> {
            throw new IllegalArgumentException("not yet implemented");
        });
        
        app.exception(Exception.class, (e, ctx) -> {
            logger.error("Exception found", e);
            ctx.status(500).result(e.getMessage());
        });

        final Micrometer micrometer = new Micrometer();
        app.get("/metrics", ctx -> {
        ctx.status(404);
        if (ctx.basicAuthCredentialsExist()) {
            final BasicAuthCredentials credentials = ctx.basicAuthCredentials();
            if ("metrics".equals(credentials.getUsername()) && "secret".equals(credentials.getPassword())) {
            ctx.status(200).result(micrometer.scrape());
            }
        }
        });

        final Executor executor = CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS);
        app.get("/wait", ctx -> {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "done", executor);
            ctx.result(future);
        });

        app.get("/weather/:city", ctx -> {
            String city = ctx.pathParam("city");
            ctx.result(Request.Get("https://wttr.in/" + city + "?format=3").execute()
                .returnContent().asBytes())
                .contentType("text/plain; charset=utf-8");
        });

        app.get("/", mainHandler());
        app.start(8000);
    }

    static Handler mainHandler() {
        return ctx -> {
            String userAgent = ctx.userAgent() != null ? ctx.userAgent() : "-";
            logger.info("This is an informative logging message, user agent [{}]", userAgent);
            ctx.result("Absolutely perfect");
        };
    }
}