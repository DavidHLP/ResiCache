package com.david.spring.cache.redis.generator;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.lang.NonNull;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

import static com.david.spring.cache.redis.core.CacheConstants.*;

@Slf4j
public class CacheKeyGenerator implements KeyGenerator {

	@Override
	@NonNull
	public Object generate(Object target, Method method, Object... params) {
		StringBuilder keyBuilder = new StringBuilder();

		keyBuilder.append(target.getClass().getSimpleName())
				.append(CACHE_KEY_SEPARATOR)
				.append(method.getName());

		if (params.length > 0) {
			String paramsString = Arrays.stream(params)
					.map(this::paramToString)
					.collect(Collectors.joining(CACHE_KEY_SEPARATOR));

			keyBuilder.append(CACHE_KEY_SEPARATOR).append(paramsString);
		}

		String key = keyBuilder.toString();

		if (key.length() > MAX_KEY_LENGTH) {
			String hash = DigestUtil.md5Hex(key);
			key = key.substring(0, MAX_KEY_LENGTH - HASH_LENGTH - 1) + CACHE_KEY_SEPARATOR + hash;
			log.debug("Generated key was too long, truncated and hashed: {}", key);
		}

		log.debug("Generated cache key: {}", key);
		return key;
	}

	private String paramToString(Object param) {
		if (param == null) {
			return NULL_PARAM;
		}

		if (param.getClass().isArray()) {
			if (param instanceof Object[]) {
				return Arrays.deepToString((Object[]) param);
			} else {
				return ObjectUtils.nullSafeToString(param);
			}
		}

		String paramStr = param.toString();
		return StringUtils.hasText(paramStr) ? paramStr : NULL_PARAM;
	}
}