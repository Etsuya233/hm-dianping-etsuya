package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@SpringBootTest
public class ImportGeoData {
	@Autowired
	public StringRedisTemplate stringRedisTemplate;
	@Autowired
	public IShopService shopService;

	@Test
	public void test(){
		List<Shop> list = shopService.list();
		list.forEach(shop -> {
			Long typeId = shop.getTypeId();
			String key = RedisConstants.SHOP_GEO_KEY + typeId;
			stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
		});
	}
}
