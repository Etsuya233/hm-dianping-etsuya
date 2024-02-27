package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
	@Bean
	public RedissonClient redissonClient(){
		Config config = new Config();
		config.useSingleServer()
				.setAddress("redis://localhost:6379")
				.setPassword("ety2004");
		return Redisson.create(config);
	}
}
