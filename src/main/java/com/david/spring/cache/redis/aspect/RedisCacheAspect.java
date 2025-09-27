package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.register.RedisCacheRegister;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class RedisCacheAspect implements Ordered {

	private final RedisCacheRegister redisCacheRegister;

	@Override
	public int getOrder() {
		return 0;
	}
}