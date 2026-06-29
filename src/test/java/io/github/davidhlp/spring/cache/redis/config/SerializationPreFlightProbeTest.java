package io.github.davidhlp.spring.cache.redis.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SerializationPreFlightProbe 单元测试(guide §115)。
 *
 * <p>{@code isEnvelope} 是纯函数,直接断言;{@code scanAndReport} 用 mock RedisConnectionFactory
 * + Cursor + ListAppender 覆盖扫描/计数/WARN 整条链(免 Testcontainers,镜像 R15 shouldWarn() 范式)。
 */
@DisplayName("SerializationPreFlightProbe")
class SerializationPreFlightProbeTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] ENVELOPE =
            b("{\"version\":2,\"payload\":{\"@class\":\"com.example.Foo\",\"v\":1}}");
    private static final byte[] LEGACY_JSON = b("{\"@class\":\"com.example.Foo\",\"v\":1}");
    private static final byte[] JDK_BYTES = new byte[]{(byte) 0xac, (byte) 0xed, 0x00, 0x05};

    @Nested
    @DisplayName("isEnvelope (detection)")
    class IsEnvelopeTests {

        @Test
        @DisplayName("envelope JSON (version+payload) → true")
        void envelope_returnsTrue() {
            assertThat(SerializationPreFlightProbe.isEnvelope(ENVELOPE)).isTrue();
        }

        @Test
        @DisplayName("legacy JSON without version/payload → false")
        void legacyJson_returnsFalse() {
            assertThat(SerializationPreFlightProbe.isEnvelope(LEGACY_JSON)).isFalse();
        }

        @Test
        @DisplayName("JDK-serialized bytes (non-JSON) → false")
        void jdkBytes_returnFalse() {
            assertThat(SerializationPreFlightProbe.isEnvelope(JDK_BYTES)).isFalse();
        }

        @Test
        @DisplayName("null/empty → false")
        void nullOrEmpty_returnFalse() {
            assertThat(SerializationPreFlightProbe.isEnvelope(null)).isFalse();
            assertThat(SerializationPreFlightProbe.isEnvelope(new byte[0])).isFalse();
        }
    }

    @Nested
    @DisplayName("scanAndReport")
    class ScanAndReportTests {

        private ListAppender<ILoggingEvent> appender;
        private Logger probeLogger;

        @BeforeEach
        void attachAppender() {
            probeLogger = (Logger) LoggerFactory.getLogger(SerializationPreFlightProbe.class);
            appender = new ListAppender<>();
            appender.start();
            probeLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            probeLogger.detachAppender(appender);
            appender.stop();
        }

        private List<ILoggingEvent> warnings() {
            return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        }

        @SuppressWarnings("unchecked")
        private ObjectProvider<RedisConnectionFactory> factoryProvider(byte[] singleValue) {
            RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
            RedisConnection conn = mock(RedisConnection.class);
            RedisKeyCommands keyCmds = mock(RedisKeyCommands.class);
            RedisStringCommands strCmds = mock(RedisStringCommands.class);
            Cursor<byte[]> cursor = mock(Cursor.class);
            when(factory.getConnection()).thenReturn(conn);
            when(conn.keyCommands()).thenReturn(keyCmds);
            when(conn.stringCommands()).thenReturn(strCmds);
            when(keyCmds.scan(any(ScanOptions.class))).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(b("k1"));
            when(strCmds.get(any(byte[].class))).thenReturn(singleValue);
            ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(factory);
            return provider;
        }

        @Test
        @DisplayName("non-envelope value present → WARN reports legacy count")
        void legacyValue_warns() {
            RedisProCacheProperties props = new RedisProCacheProperties();
            props.getSerializer().setProbeSampleSize(100);
            SerializationPreFlightProbe probe = new SerializationPreFlightProbe(
                    factoryProvider(LEGACY_JSON), props);

            probe.scanAndReport();

            List<ILoggingEvent> warns = warnings();
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0).getFormattedMessage()).contains("1/1").contains("envelope");
        }

        @Test
        @DisplayName("all values envelope → no WARN")
        void allEnvelope_noWarn() {
            RedisProCacheProperties props = new RedisProCacheProperties();
            props.getSerializer().setProbeSampleSize(100);
            SerializationPreFlightProbe probe = new SerializationPreFlightProbe(
                    factoryProvider(ENVELOPE), props);

            probe.scanAndReport();

            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("no RedisConnectionFactory → no-op, no WARN")
        void noFactory_noOp() {
            @SuppressWarnings("unchecked")
            ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
            RedisProCacheProperties props = new RedisProCacheProperties();
            SerializationPreFlightProbe probe = new SerializationPreFlightProbe(provider, props);

            probe.scanAndReport();

            assertThat(warnings()).isEmpty();
        }
    }
}
