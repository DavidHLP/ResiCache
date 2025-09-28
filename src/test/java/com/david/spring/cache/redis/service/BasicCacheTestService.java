package com.david.spring.cache.redis.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Serializable;


@Slf4j
@Service
public class BasicCacheTestService {
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class User implements Serializable {
	private Long id;
	private String name;
	private String email;
}