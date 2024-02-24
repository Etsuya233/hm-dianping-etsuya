package com.hmdp.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class RedisCacheUtils {

	private final StringRedisTemplate stringRedisTemplate;

	public RedisCacheUtils(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	//TTL过期设置
	public void set(String key, Object object, Long ttl, TimeUnit timeUnit){
		stringRedisTemplate.opsForValue().set(
				key,
				JSONUtil.toJsonStr(object),
				ttl,
				timeUnit
		);
	}

	public void set(String key, Object object, Long ttl){
		stringRedisTemplate.opsForValue().set(
				key,
				JSONUtil.toJsonStr(object),
				ttl,
				TimeUnit.MILLISECONDS
		);
	}

	//逻辑过期设置
	public void setLogical(String key, Object object, Long ttl, ChronoUnit timeUnit){
		RedisData redisData = new RedisData();
		redisData.setExpireTime(LocalDateTime.now().plus(ttl, timeUnit));
		redisData.setData(object);
		stringRedisTemplate.opsForValue().set(
				key,
				JSONUtil.toJsonStr(redisData)
		);
	}

	//读取缓存，并利用缓存空值解决穿透问题
	public <T, R> T getWithPassThrough(String keyPrefix, R id, Class<T> clazz, Function<R, T> dbFallback, Long ttlFallback, TimeUnit timeUnitFallback){
		String key = keyPrefix + id;
		String json = stringRedisTemplate.opsForValue().get(key);
		if(StrUtil.isNotBlank(json)){
			return JSONUtil.toBean(json, clazz);
		}
		if(json != null){ //不是null又空-》空字符串
			return null;
		}
		//缓存穿透
		T res = dbFallback.apply(id);
		if(res == null){
			stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
			return null;
		}
		this.set(key, res, ttlFallback, timeUnitFallback);
		return res;
	}

	//重建缓存线程池
	private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	//读取缓存，并利用逻辑过期来解决缓存击穿问题
	public <T, R> T getLogical(String keyPrefix, R id, Class<T> clazz,
							   Function<R, T> dbFallback, String lockPrefixFallback,
							   Long ttlFallback, ChronoUnit timeUnitFallback){
		String key = keyPrefix + id;
		String json = stringRedisTemplate.opsForValue().get(key);
		if(StrUtil.isBlank(json)){
			return null;
		}
		//注意，里面的Object不会根据泛型转换，因为泛型被擦除了。里面的object会被转化成JsonObject。
		//如果是确定类型的泛型，就会被装化成确定类型的！！
		RedisData<T> data = JSONUtil.toBean(json, new TypeReference<RedisData<T>>() {}, false);
		//查看是否过期
		if(data.getExpireTime().isBefore(LocalDateTime.now())){
			if(tryLock(lockPrefixFallback + id)){
				//double check
				RedisData<T> shopRedisData = JSONUtil.toBean(stringRedisTemplate.opsForValue().get(key),
						new TypeReference<RedisData<T>>() {}, false);
				if(shopRedisData.getExpireTime().isBefore(LocalDateTime.now())){
					CACHE_REBUILD_EXECUTOR.submit(() -> {
						log.info("重建缓存！！！");
						T res = dbFallback.apply(id);
						RedisData<T> redisData = new RedisData<>();
						redisData.setData(res);
						redisData.setExpireTime(LocalDateTime.now().plus(ttlFallback, timeUnitFallback));
						stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
						unlock(lockPrefixFallback + id);
					});
				}
			}
		}
		return JSONUtil.toBean((JSONObject) data.getData(), clazz);
	}

	//互斥锁的开启
	private boolean tryLock(String key){
		return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "qwq", Duration.ofSeconds(RedisConstants.LOCK_SHOP_TTL)));
	}

	//互斥锁的关闭
	private boolean unlock(String key){
		return Boolean.TRUE.equals(stringRedisTemplate.delete(key));
	}

}
