package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisCacheUtils;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

	private final StringRedisTemplate stringRedisTemplate;
	private final ObjectMapper objectMapper;
	private final RedisCacheUtils redisCacheUtils;

	@Override
	public Result queryShopById(Long id) throws JsonProcessingException {
		Shop shop = redisCacheUtils.getWithPassThrough(
				RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
				this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
		);
		if(shop == null) return Result.fail("店铺不存在！");
		return Result.ok(shop);
	}

	//互斥锁的实现
	public Result queryShopByIdWithLock(Long id) throws JsonProcessingException {
		String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
		Shop shop;
		if(json == null){
			try {
				if(!tryLock(RedisConstants.LOCK_SHOP_KEY + id)){
					Thread.sleep(100);
					return queryShopByIdWithLock(id);
				}
				shop = getById(id);
				if(shop == null){
					//缓存穿透
					stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
					return Result.fail("店铺不存在！");
				}
				stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, objectMapper.writeValueAsString(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			} finally {
				unlock(RedisConstants.LOCK_SHOP_KEY + id);
			}
			return Result.ok(shop);
		}
		if(json.isEmpty()) return Result.fail("店铺不存在！");
		shop = objectMapper.readValue(json, Shop.class);
		return Result.ok(shop);
	}

	//重建缓存线程池
	private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	//逻辑过期的实现
	public Result queryShopByIdWithLogicalExpire(Long id){
		Shop shop = redisCacheUtils.getLogical(
				RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
				this::getById, RedisConstants.LOCK_SHOP_KEY, RedisConstants.LOCK_SHOP_TTL, ChronoUnit.SECONDS
		);
		if(shop == null){
			return Result.fail("商铺不存在！");
		}
		return Result.ok(shop);
	}

	@Override
	public void saveShop(Shop shop) {
		save(shop);
	}

	@Override
	@Transactional
	public void updateShop(Shop shop) {
		updateById(shop);
		//主动剔除
		stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
	}

	//互斥锁的开启
	private boolean tryLock(String key){
		return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "qwq", Duration.ofSeconds(RedisConstants.LOCK_SHOP_TTL)));
	}

	//互斥锁的关闭
	private boolean unlock(String key){
		return Boolean.TRUE.equals(stringRedisTemplate.delete(key));
	}

	//向Redis中添加店铺数据
	@Override
	public void addShop2RedisWithLogicalExpire(Long id){
//		try {
//			Thread.sleep(500);
//		} catch (InterruptedException e) {
//			throw new RuntimeException(e);
//		}
		Shop shop = getById(id);
		RedisData<Shop> shopRedisData = new RedisData<>();
		shopRedisData.setData(shop);
		shopRedisData.setExpireTime(LocalDateTime.now().plusMinutes(RedisConstants.CACHE_SHOP_TTL));
		stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shopRedisData));
	}
}
