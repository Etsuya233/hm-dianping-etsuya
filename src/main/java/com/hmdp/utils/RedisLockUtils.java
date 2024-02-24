package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;

@Component
public class RedisLockUtils {

	private final StringRedisTemplate stringRedisTemplate;
	private final static String KEY_PREFIX = "lock:";
	@Value("${hm.server.worker-id}")
	private String workerId;
	@Value("${hm.server.datacenter-id}")
	private String datacenterId;
	//DefaultRedisScript是RedisScript的儿子
	private final static DefaultRedisScript<Long> REDIS_SCRIPT;

	static {
		//初始化RedisScript
		REDIS_SCRIPT = new DefaultRedisScript<>();
		REDIS_SCRIPT.setResultType(Long.class);
		REDIS_SCRIPT.setLocation(new ClassPathResource("scripts/redis_unlock.lua"));
	}

	public RedisLockUtils(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	public boolean tryLock(String key, long timeoutSec){
		Boolean lockedFlag = stringRedisTemplate.opsForValue().setIfAbsent(
				KEY_PREFIX + key,
				workerId + ":" + datacenterId + ":" + Thread.currentThread().getId(),
				Duration.ofSeconds(timeoutSec));
		return Boolean.TRUE.equals(lockedFlag);
	}

	public boolean unlockWithoutLua(String key){
		//判断锁是不是自己的
		String str = stringRedisTemplate.opsForValue().get(KEY_PREFIX + key);
		if(str == null) return false;
		if(!str.equals(workerId + ":" + datacenterId + ":" + Thread.currentThread().getId())) return false;
		//是我的锁，现在删掉。
		//但是我现在堵塞了！！！其他机器在我堵塞期间拿到了锁。
		//我恢复后就会立即删掉别人的锁！！！！！
		return Boolean.TRUE.equals(stringRedisTemplate.delete(KEY_PREFIX + key));
	}

	public boolean unlock(String key){
		String str = stringRedisTemplate.opsForValue().get(KEY_PREFIX + key);
		if(str == null) return false;
		Long execute = stringRedisTemplate.execute(
				REDIS_SCRIPT,
				List.of(KEY_PREFIX + key),
				workerId + ":" + datacenterId + ":" + Thread.currentThread().getId());
		return execute != null && execute == 1;
	}

}
