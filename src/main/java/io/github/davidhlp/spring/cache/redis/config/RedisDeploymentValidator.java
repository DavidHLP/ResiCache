package io.github.davidhlp.spring.cache.redis.config;



import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redis 部署配置跨字段校验(P1-CONFIG-001)。
 *
 * <p>把 mode 相关的一致性规则收口为类级 validator,violation 绑定到具体 property
 * node(而非整对象),使启动失败信息含完整属性路径:
 * <ul>
 *   <li>{@code mode=cluster} → {@code clusterNodes} 非空</li>
 *   <li>{@code mode=sentinel} → {@code sentinelMaster} 与 {@code sentinelNodes} 非空</li>
 *   <li>{@code tlsRequired=true} → {@code tlsEnabled} 必须为 {@code true}</li>
 *   <li>未知 mode 值 → 拒绝(绑定到 {@code mode} node)</li>
 * </ul>
 */
@Documented
@Constraint(validatedBy = RedisDeploymentValidator.RedisDeploymentChecks.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface RedisDeploymentValidator {

    String message() default "invalid redis deployment configuration";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * 校验逻辑 — 绑定违规到具体 property node。
     */
    class RedisDeploymentChecks implements
            ConstraintValidator<RedisDeploymentValidator, RedisProCacheProperties.RedisDeploymentProperties> {

        @Override
        public boolean isValid(RedisProCacheProperties.RedisDeploymentProperties value,
                               ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            boolean valid = true;
            String mode = value.getMode() == null ? "" : value.getMode().trim();

            switch (mode) {
                case "single" -> {
                    // host/port 合法即够(默认即有值)
                }
                case "cluster" -> {
                    if (value.getClusterNodes() == null || value.getClusterNodes().isEmpty()) {
                        context.buildConstraintViolationWithTemplate(
                                        "mode=cluster requires non-empty clusterNodes")
                                .addPropertyNode("clusterNodes")
                                .addConstraintViolation();
                        valid = false;
                    }
                }
                case "sentinel" -> {
                    if (value.getSentinelMaster() == null || value.getSentinelMaster().isBlank()) {
                        context.buildConstraintViolationWithTemplate(
                                        "mode=sentinel requires sentinelMaster")
                                .addPropertyNode("sentinelMaster")
                                .addConstraintViolation();
                        valid = false;
                    }
                    if (value.getSentinelNodes() == null || value.getSentinelNodes().isEmpty()) {
                        context.buildConstraintViolationWithTemplate(
                                        "mode=sentinel requires non-empty sentinelNodes")
                                .addPropertyNode("sentinelNodes")
                                .addConstraintViolation();
                        valid = false;
                    }
                }
                default -> {
                    context.buildConstraintViolationWithTemplate(
                                    "redis.mode must be one of [single, cluster, sentinel] but was '" + mode + "'")
                            .addPropertyNode("mode")
                            .addConstraintViolation();
                    valid = false;
                }
            }

            if (value.isTlsRequired() && !value.isTlsEnabled()) {
                context.buildConstraintViolationWithTemplate(
                                "tlsRequired=true requires tlsEnabled=true")
                        .addPropertyNode("tlsEnabled")
                        .addConstraintViolation();
                valid = false;
            }

            return valid;
        }
    }
}
