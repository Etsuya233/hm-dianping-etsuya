package com.hmdp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

	Result queryShopById(Long id) throws JsonProcessingException;

	void saveShop(Shop shop);

	void updateShop(Shop shop);

	Result queryShopByIdWithLogicalExpire(Long id);

	void addShop2RedisWithLogicalExpire(Long id);

	Result queryShopByTypeAndLocation(Integer typeId, Integer current, Double x, Double y);
}
