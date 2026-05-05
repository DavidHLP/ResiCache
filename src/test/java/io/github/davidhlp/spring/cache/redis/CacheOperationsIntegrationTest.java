package io.github.davidhlp.spring.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@ActiveProfiles("integration-test")
@ContextConfiguration(classes = TestRedisConfiguration.class)
@DisplayName("Cache Operations Integration Tests")
class CacheOperationsIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    private ValueOperations<String, Object> valueOperations;

    @BeforeEach
    void setUp() {
        valueOperations = redisCacheTemplate.opsForValue();
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Nested
    @DisplayName("Basic Write and Read Operations")
    class BasicWriteReadTests {

        @Test
        @DisplayName("should write and read string value")
        void writeAndReadStringValue() {
            String key = "test:string:key";
            String value = "test-value-123";

            valueOperations.set(key, value);
            Object retrieved = valueOperations.get(key);

            assertThat(retrieved).isEqualTo(value);
        }

        @Test
        @DisplayName("should write and read complex object")
        void writeAndReadComplexObject() {
            String key = "test:object:key";
            TestUser user = new TestUser(1L, "John Doe", "john@example.com");

            valueOperations.set(key, user);
            Object retrieved = valueOperations.get(key);

            assertThat(retrieved).isInstanceOf(TestUser.class);
            TestUser retrievedUser = (TestUser) retrieved;
            assertThat(retrievedUser.getId()).isEqualTo(1L);
            assertThat(retrievedUser.getName()).isEqualTo("John Doe");
            assertThat(retrievedUser.getEmail()).isEqualTo("john@example.com");
        }

        @Test
        @DisplayName("should return null for non-existent key")
        void readNonExistentKeyReturnsNull() {
            Object result = valueOperations.get("non:existent:key");
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("TTL Operations")
    class TtlOperationsTests {

        @Test
        @DisplayName("should expire key after TTL")
        void keyExpiresAfterTtl() throws InterruptedException {
            String key = "test:ttl:key";
            String value = "ttl-value";

            valueOperations.set(key, value, Duration.ofSeconds(2));
            assertThat(valueOperations.get(key)).isEqualTo(value);

            TimeUnit.SECONDS.sleep(3);

            assertThat(valueOperations.get(key)).isNull();
        }

        @Test
        @DisplayName("should check if key exists")
        void checkKeyExists() {
            String key = "test:exists:key";
            valueOperations.set(key, "value");

            Boolean exists = redisCacheTemplate.hasKey(key);
            assertThat(exists).isTrue();
        }
    }

    @Nested
    @DisplayName("Delete Operations")
    class DeleteOperationsTests {

        @Test
        @DisplayName("should delete existing key")
        void deleteExistingKey() {
            String key = "test:delete:key";
            valueOperations.set(key, "value");

            Boolean deleted = redisCacheTemplate.delete(key);
            assertThat(deleted).isTrue();
            assertThat(valueOperations.get(key)).isNull();
        }

        @Test
        @DisplayName("should handle delete for non-existent key")
        void deleteNonExistentKey() {
            Boolean deleted = redisCacheTemplate.delete("non:existent:key");
            assertThat(deleted).isFalse();
        }
    }

    static class TestUser {
        private Long id;
        private String name;
        private String email;

        public TestUser() {}

        public TestUser(Long id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }
}
