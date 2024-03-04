package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisLockUtils;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

	private final ISeckillVoucherService seckillVoucherService;
	private final SnowflakeIdWorker snowflakeIdWorker;
	private final RedissonClient redissonClient;
	private final StringRedisTemplate stringRedisTemplate;
	//Lua脚本
	private final DefaultRedisScript<Long> seckillVoucherLua = new DefaultRedisScript<>();
	//线程池
	private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
	private IVoucherOrderService currentProxy;

	@PostConstruct
	private void init(){
		EXECUTOR_SERVICE.submit(() -> {
			while (true){
				try {
					//1，读取消息：xreadgroup group g1 c1 count 1 block 2000 streams stream.orders >
					List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
							Consumer.from("g1", "c1"),
							StreamReadOptions.empty().block(Duration.ofSeconds(2)).count(1),
							StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
					);
					//2，判断消息是否获取成功并解析消息
					if(records == null || records.isEmpty()){
						continue;
					}
					MapRecord<String, Object, Object> entries = records.get(0);
					Map<Object, Object> value = entries.getValue(); //从MapRecord解析出key value的map
					VoucherOrder voucherOrder = BeanUtil.mapToBean(value, VoucherOrder.class, false, CopyOptions.create());
					//3，下单
					currentProxy.handleSeckillVoucher(voucherOrder);
					//4，ACK sack stream.orders g1 id...
					stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", entries.getId());
				} catch (Exception e){
					log.error("订单处理异常", e);
					//重新投递
					while(true){
						try {
							// xreadgroup group g1 c1 count 1 streams stream.orders 0
							List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
									Consumer.from("g1", "c1"),
									StreamReadOptions.empty().count(1),
									StreamOffset.create("stream.orders", ReadOffset.from("0"))
							);
							//pending-list有无消息?
							if(records == null || records.isEmpty()){
								break;
							}
							MapRecord<String, Object, Object> entries = records.get(0);
							Map<Object, Object> value = entries.getValue(); //从MapRecord解析出key value的map
							VoucherOrder voucherOrder = BeanUtil.mapToBean(value, VoucherOrder.class, false, CopyOptions.create());
							//3，下单
							currentProxy.handleSeckillVoucher(voucherOrder);
							//4，ACK sack stream.orders g1 id...
							stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", entries.getId());
						} catch (Exception e1){
							log.error("订单再处理异常", e1);
							try {
								Thread.sleep(200);
							} catch (InterruptedException ex) {
								throw new RuntimeException(ex);
							}
						}
					}
				}
			}
		});
	}

	public VoucherOrderServiceImpl(ISeckillVoucherService seckillVoucherService, SnowflakeIdWorker snowflakeIdWorker, RedisLockUtils redisLockUtils, RedissonClient redissonClient, StringRedisTemplate stringRedisTemplate) {
		this.seckillVoucherService = seckillVoucherService;
		this.snowflakeIdWorker = snowflakeIdWorker;
		this.redissonClient = redissonClient;
		this.stringRedisTemplate = stringRedisTemplate;
		this.seckillVoucherLua.setLocation(new ClassPathResource("scripts/redis_seckill_voucher.lua"));
		this.seckillVoucherLua.setResultType(Long.class);
	}

	@Override
	public Result getSeckillVoucher(Long voucherId) {
		SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
		if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now()) || seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
			return Result.fail("不在可以抢购优惠券的时间之内！");
		}
		if(seckillVoucher.getStock() <= 0){
			return Result.fail("优惠券库存不足！");
		}
		//3，使用Redisson
		RLock rLock = redissonClient.getLock(RedisConstants.SECKILL_LOCK + UserHolder.getUser().getId());
		if(!rLock.tryLock()){
			return Result.fail("抢购失败！");
		}
		try{
			currentProxy = (IVoucherOrderService) AopContext.currentProxy();
			return currentProxy.realGetSeckillVoucher(voucherId, seckillVoucher);
		} finally {
			rLock.unlock();
		}
	}

	@Transactional
	public Result realGetSeckillVoucher(Long voucherId, SeckillVoucher seckillVoucher){
		long id = snowflakeIdWorker.nextId();
		Long result = stringRedisTemplate.execute(seckillVoucherLua, List.of(),
				UserHolder.getUser().getId().toString(), voucherId.toString(), String.valueOf(id));
		//其实这里应该给前端返回一些东西的！
		if(result == null){
			return Result.fail("抢购失败！内部出错！");
		} else if(result == 1) {
			return Result.fail("优惠券库存不足。");
		} else if(result == 2) {
			return Result.fail("该用户持该优惠券已达上限！");
		}
		return Result.ok();
	}

	@Transactional
	public void handleSeckillVoucher(VoucherOrder voucherOrder) {
		save(voucherOrder);
		seckillVoucherService.lambdaUpdate()
				.setSql("stock = stock - 1")
				.eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
				.update();
	}
}
