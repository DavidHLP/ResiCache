package com.example.consumer;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ordinary Boot application using only the installed JAR and its POM. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableCaching
public class BootConsumer {
    @Bean
    public Service service() {
        return new Service();
    }

    public static class Service {
        static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();

        @RedisCacheable(value = "external-sync", key = "#id", sync = true)
        public String load(String id) {
            CALLS.incrementAndGet();
            return "value-" + id;
        }
    }

    public static void main(String[] args) throws Exception {
        String profile = args[0];
        try (var app = new SpringApplicationBuilder(BootConsumer.class)
                .web(WebApplicationType.NONE)
                .run("--spring.data.redis.port=" + args[1], "--resi-cache.redis.port=" + args[1],
                        "--resi-cache.metrics.enabled=" + profile.equals("observability"),
                        "--resi-cache.protection.bloom-filter-enabled=false")) {
            CacheManager manager = app.getBean(CacheManager.class);
            if (!manager.getClass().getName().contains("RedisProCacheManager")) {
                throw new AssertionError("Packaged Boot auto-configuration was not discovered");
            }
            var cache = manager.getCache("external-" + profile);
            cache.put("key", "value");
            if (!"value".equals(cache.get("key", String.class))) {
                throw new AssertionError("Packaged cache read/write failed");
            }
            if (profile.equals("redisson")) {
                Service service = app.getBean(Service.class);
                if (!service.load("1").equals(service.load("1")) || Service.CALLS.get() != 1) {
                    throw new AssertionError("Packaged sync cache path failed");
                }
            }
            if (profile.equals("observability")) {
                if (!app.containsBean("redisCacheHealthIndicator") || app.getBeansOfType(
                        Class.forName("io.micrometer.core.instrument.MeterRegistry")).isEmpty()) {
                    throw new AssertionError("Optional observability did not assemble");
                }
            }
            System.out.println("BOOT_CONSUMER_OK " + profile);
        }
    }
}
