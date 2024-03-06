package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.hmdp.utils.SystemConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoLocation;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
		Shop shop = getById(id);
		RedisData<Shop> shopRedisData = new RedisData<>();
		shopRedisData.setData(shop);
		shopRedisData.setExpireTime(LocalDateTime.now().plusMinutes(RedisConstants.CACHE_SHOP_TTL));
		stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shopRedisData));
	}

	@Override
	public Result queryShopByTypeAndLocation(Integer typeId, Integer current, Double x, Double y) {
		if(x == null || y == null){
			Page<Shop> page = lambdaQuery().eq(Shop::getTypeId, typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
			return Result.ok(page);
		}
		//查询id
		int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE, end = current * SystemConstants.DEFAULT_PAGE_SIZE;
		GeoResults<RedisGeoCommands.GeoLocation<String>> searched = stringRedisTemplate.opsForGeo().search(
				RedisConstants.SHOP_GEO_KEY + typeId,
				new GeoReference.GeoCoordinateReference<>(x, y),
				new Distance(5000),
				RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
						.includeDistance()
						.limit(end));
		if(searched == null) return Result.ok();
		List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = searched.getContent();
		//截取
		List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults = content.subList(Math.min(from, content.size()), Math.min(end, content.size()));
		//解析
		if(geoResults.isEmpty()) return Result.ok();
		List<String> shopIds = geoResults.stream().map(a -> a.getContent().getName()).collect(Collectors.toList());
		String shopIdStr = StrUtil.join(", ", shopIds);
		List<Shop> shops = lambdaQuery()
				.in(Shop::getId, shopIds)
				.last("ORDER BY FIELD(id, " + shopIdStr + ")")
				.list();
		for(int i = 0; i < shops.size(); i++){
			shops.get(i).setDistance(geoResults.get(i).getDistance().getValue());
		}
		return Result.ok(shops);
	}
}
