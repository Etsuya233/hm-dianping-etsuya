package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

	@Override
	public Result queryShopById(Long id) throws JsonProcessingException {
		String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
		Shop shop;
		if(json == null){
			try {
				if(!tryLock(RedisConstants.LOCK_SHOP_KEY)){
					Thread.sleep(100);
					return queryShopById(id);
				}
				Thread.sleep(300);
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
				unlock(RedisConstants.LOCK_SHOP_KEY);
			}
			return Result.ok(shop);
		}
		if(json.isEmpty()) return Result.fail("店铺不存在！");
		shop = objectMapper.readValue(json, Shop.class);
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

	private boolean tryLock(String key){
		return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "qwq", Duration.ofMinutes(RedisConstants.LOCK_SHOP_TTL)));
	}

	private boolean unlock(String key){
		return Boolean.TRUE.equals(stringRedisTemplate.delete(key));
	}


}
