#!/usr/bin/env bash
# RM-010 external consumer gate — prove the public contract from the PACKAGED JAR.
#
# 1. ./mvnw -DskipTests package -B (rebuilds target/ResiCache-<version>.jar)
# 2. Compiles a minimal consumer in an ISOLATED temp directory using ONLY the
#    packaged JAR + declared compile-scope dependencies (no target/classes,
#    no test-classes, no source paths, no same-package access).
# 3. Runs the consumer's pure-value protocol demo (no Spring context, no Redis —
#    compile-plus-value-path proof only; real Redis behavior is NOT claimed here).
set -euo pipefail
JH="${RESICACHE_JDK21:-$HOME/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS}"
export JAVA_HOME="$JH"
MVNW="./mvnw"

echo "== 1) packaging =="
"$MVNW" -q -DskipTests package -B
JAR=$(ls target/ResiCache-*.jar | grep -v -- '-sources' | grep -v -- '-javadoc' | head -1)
echo "JAR: $JAR"
echo "JAR sha256: $(sha256sum "$JAR")"

echo "== 2) declared compile classpath (dependency:build-classpath) =="
CP_FILE=$(mktemp)
"$MVNW" -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -B
DECLARED_CP=$(cat "$CP_FILE")

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src/com/example/consumer"

cat > "$TMP/src/com/example/consumer/ExternalConsumerDemo.java" <<'EOF'
package com.example.consumer;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.FlowControl;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;

/**
 * External consumer sample (RM-010) — annotation surface + one supported
 * extension seam, using ONLY classified public contract types.
 */
public class ExternalConsumerDemo {

    @RedisCacheable(value = "demo", key = "#id", ttl = 60)
    public String load(String id) {
        return "value-" + id;
    }

    /** Supported extension: a custom CacheHandler. */
    @HandlerPriority(HandlerOrder.TTL)
    static class DemoHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.miss());
        }
    }

    /** Supported extension: a custom ChainObserver with scope token. */
    static class DemoObserver implements ChainObserver {
        @Override
        public Object onChainStart(CacheContext context) {
            return "demo-token";
        }

        @Override
        public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
            // token is the same reference returned by this observer's onChainStart
        }
    }

    public static void main(String[] args) {
        // Value-path protocol demo (no Spring, no Redis):
        CacheResult hit = CacheResult.success("bytes".getBytes());
        if (!hit.isSuccess() || hit.outcome() != CacheResult.Outcome.SUCCESS) {
            throw new AssertionError("success contract broken");
        }
        CacheResult failure = CacheResult.failure(
                CacheOperation.PUT, CacheResult.FailureKind.REDIS, new IllegalStateException("x"));
        if (failure.isSuccess() || failure.operation() != CacheOperation.PUT) {
            throw new AssertionError("failure contract broken");
        }
        HandlerResult r = HandlerResult.terminate(hit);
        if (r.decision() != FlowControl.TERMINATE || !r.shouldTerminate()) {
            throw new AssertionError("flow control contract broken");
        }
        DemoHandler handler = new DemoHandler();
        DemoObserver observer = new DemoObserver();
        if (observer.onChainStart(null) != "demo-token") {
            throw new AssertionError("observer token contract broken");
        }
        if (handler.handle(null).decision() != FlowControl.CONTINUE) {
            throw new AssertionError("handler contract broken");
        }
        if (CachePolicyView.NONE.useBloomFilter()) {
            throw new AssertionError("policy view default contract broken");
        }
        System.out.println("EXTERNAL_CONSUMER_OK");
    }
}
EOF


echo "== 3) compiling consumer against packaged JAR only =="
mkdir -p "$TMP/classes"
"$JH/bin/javac" -cp "$JAR:$DECLARED_CP" -d "$TMP/classes" "$TMP/src/com/example/consumer/ExternalConsumerDemo.java"
echo "consumer imports (compile resolved against JAR + declared deps only): CacheHandler, ChainObserver, CacheResult(+Outcome/FailureKind), HandlerResult, FlowControl, HandlerOrder, HandlerPriority, CacheContext, CachePolicyView, @RedisCacheable"

echo "== 4) running consumer =="
"$JH/bin/java" -cp "$TMP/classes:$JAR:$DECLARED_CP" com.example.consumer.ExternalConsumerDemo
echo "RM-010 PASS: external consumer compiled from packaged JAR and ran"
