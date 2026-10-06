package io.github.davidhlp.spring.cache.redis.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Measures the actual v2 serializer/codec/storage adaptation, excluding network I/O. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CacheSerializationBenchmark {
    @Param({"64", "4096", "65536"})
    public int payloadBytes;
    private SecureJacksonRedisSerializer serializer;
    private CacheValueCodec codec;
    private ObjectMapper mapper;
    private byte[] businessBytes;
    private String value;

    @Setup
    public void setup() {
        mapper = new ObjectMapper();
        serializer = new SecureJacksonRedisSerializer(mapper,
                List.of("io.github.davidhlp.spring.cache.redis"), true, "@class", false);
        codec = new CacheValueCodec(mapper);
        value = "x".repeat(payloadBytes);
        businessBytes = serializer.serialize(value);
        if (!value.equals(storageRoundTrip())) {
            throw new IllegalStateException("storage round-trip changed the business value");
        }
    }

    /** Plain mapper reference without codec null-placeholder dispatch. */
    @Benchmark
    public byte[] mapperCodecReference() throws Exception {
        return mapper.writeValueAsBytes(mapper.readValue(businessBytes, Object.class));
    }

    /** Actual codec adaptation, retaining the existing JSON and null-placeholder contract. */
    @Benchmark
    public byte[] codecRoundTrip() {
        return codec.toValueBytes(codec.fromValueBytes(businessBytes));
    }

    /** Reference: one serializer envelope, without the writer/storage adaptation. */
    @Benchmark
    public Object serializerRoundTrip() {
        return serializer.deserialize(serializer.serialize(value));
    }

    /** Spring bytes -> codec -> CachedValue storage -> codec -> business value. */
    @Benchmark
    public Object storageRoundTrip() {
        byte[] businessBytes = serializer.serialize(value);
        CachedValue stored = CachedValue.of(codec.fromValueBytes(businessBytes), 60);
        CachedValue restored = (CachedValue) serializer.deserialize(serializer.serialize(stored));
        return serializer.deserialize(codec.toValueBytes(restored.getValue()));
    }
}
