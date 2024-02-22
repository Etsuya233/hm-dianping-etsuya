package com.hmdp.service.impl;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

	private final StringRedisTemplate stringRedisTemplate;
	private final ObjectMapper objectMapper;

	@Override
	public Result queryTypeList() {
		String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_TYPE_KEY);
		if(json != null){
			List<ShopType> list = JSONUtil.toList(json, ShopType.class);
			return Result.ok(list);
		}
		List<ShopType> list = query().orderByAsc("sort").list();
		if(list == null) return Result.fail("类型查询失败");
		stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_TYPE_KEY, JSONUtil.toJsonStr(list), RedisConstants.CACHE_TYPE_TTL, TimeUnit.MINUTES);
		return Result.ok(list);
	}
}
