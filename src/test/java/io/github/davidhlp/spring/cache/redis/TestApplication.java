package io.github.davidhlp.spring.cache.redis;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@EnableAutoConfiguration(excludeName = "org.springframework.boot.actuate.autoconfigure.metrics.SystemMetricsAutoConfiguration")
public class TestApplication {
}
