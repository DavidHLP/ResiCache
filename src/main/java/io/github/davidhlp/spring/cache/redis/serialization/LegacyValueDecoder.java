package io.github.davidhlp.spring.cache.redis.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator.TypeMatcher;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.List;

/**
 * Decodes legacy values without relaxing ResiCache's type whitelist.
 */
final class LegacyValueDecoder {

    private static final int JAVA_STREAM_MAGIC = 0xACED;

    private final String typeProperty;
    private final WhitelistPolicy whitelistPolicy;
    private final ObjectMapper validationMapper;
    private final GenericJackson2JsonRedisSerializer genericJackson;

    LegacyValueDecoder(ObjectMapper objectMapper, List<String> allowedPackagePrefixes,
                       String typeProperty) {
        this.typeProperty = typeProperty;
        this.whitelistPolicy = new WhitelistPolicy(allowedPackagePrefixes);
        this.validationMapper = objectMapper.copy();
        ObjectMapper legacyMapper = objectMapper.copy();
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(new TypeMatcher() {
                    @Override
                    public boolean match(MapperConfig<?> config, Class<?> subtype) {
                        return whitelistPolicy.isClassAllowed(subtype);
                    }
                })
                .build();
        legacyMapper.activateDefaultTypingAsProperty(
                validator, ObjectMapper.DefaultTyping.EVERYTHING, "@class");
        GenericJackson2JsonRedisSerializer.registerNullValueSerializer(legacyMapper, "@class");
        this.genericJackson = new GenericJackson2JsonRedisSerializer(legacyMapper);
    }

    Object decode(byte[] bytes, SerializationMigrationProperties.LegacySerializer serializer) {
        if (bytes == null || bytes.length == 0) {
            throw new SerializationException("Legacy value is empty");
        }
        return switch (serializer) {
            case GENERIC_JACKSON -> decodeGenericJackson(bytes);
            case JDK -> decodeJdk(bytes);
        };
    }

    private Object decodeGenericJackson(byte[] bytes) {
        validateJsonTypeIds(bytes);
        Object value = genericJackson.deserialize(bytes);
        requireAllowedValue(value);
        return value;
    }

    private Object decodeJdk(byte[] bytes) {
        if (bytes.length < 2 || (bytes[0] & 0xFF) != (JAVA_STREAM_MAGIC >>> 8)
                || (bytes[1] & 0xFF) != (JAVA_STREAM_MAGIC & 0xFF)) {
            throw new SerializationException("Legacy value is not a JDK serialization stream");
        }
        try (RestrictedObjectInputStream input =
                     new RestrictedObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object value = input.readObject();
            requireAllowedValue(value);
            return value;
        } catch (IOException | ClassNotFoundException ex) {
            throw new SerializationException("Could not safely decode JDK legacy value", ex);
        }
    }

    private void validateJsonTypeIds(byte[] bytes) {
        try (JsonParser parser = validationMapper.createParser(bytes)) {
            boolean expectsTypeValue = false;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.FIELD_NAME) {
                    expectsTypeValue = typeProperty.equals(parser.currentName())
                            || "@class".equals(parser.currentName());
                } else if (expectsTypeValue && token == JsonToken.VALUE_STRING) {
                    requireAllowedClassName(parser.getText());
                    expectsTypeValue = false;
                } else if (expectsTypeValue && token.isScalarValue()) {
                    throw new SerializationException("Legacy type id must be a string");
                }
            }
        } catch (SerializationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new SerializationException("Legacy JSON is malformed", ex);
        }
    }

    private void requireAllowedValue(Object value) {
        if (value != null && !whitelistPolicy.isClassAllowed(value.getClass())) {
            throw new SerializationException("Legacy value type is not in deserialization whitelist: "
                    + value.getClass().getName());
        }
    }

    private void requireAllowedClassName(String className) {
        if (!whitelistPolicy.isClassNameAllowed(className)) {
            throw new SerializationException(
                    "Legacy type is not in deserialization whitelist: " + className);
        }
    }

    private final class RestrictedObjectInputStream extends ObjectInputStream {

        RestrictedObjectInputStream(InputStream input) throws IOException {
            super(input);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor)
                throws IOException, ClassNotFoundException {
            String className = descriptor.getName();
            if (className.startsWith("[")) {
                Class<?> arrayClass = super.resolveClass(descriptor);
                Class<?> component = arrayClass;
                while (component.isArray()) {
                    component = component.getComponentType();
                }
                if (component.isPrimitive() || whitelistPolicy.isClassAllowed(component)) {
                    return arrayClass;
                }
                throw new InvalidClassException("Legacy array type is not in whitelist", className);
            }
            requireAllowedClassName(className);
            return super.resolveClass(descriptor);
        }

        @Override
        protected Class<?> resolveProxyClass(String[] interfaces) throws IOException {
            throw new InvalidClassException("Proxy classes are not allowed in legacy values");
        }
    }
}
