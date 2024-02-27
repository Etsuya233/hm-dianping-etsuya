package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
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
	private final RedisLockUtils redisLockUtils;
	private final RedissonClient redissonClient;
	private final StringRedisTemplate stringRedisTemplate;
	//Lua脚本
	private final DefaultRedisScript<Long> seckillVoucherLua = new DefaultRedisScript<>();
	//阻塞队列
	private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
	//线程池
	private static final ExecutorService ORDER_TASKS_EXECUTOR = Executors.newSingleThreadExecutor();
	private IVoucherOrderService currentProxy;

	@PostConstruct
	private void init(){
		//开始监听阻塞队列
		ORDER_TASKS_EXECUTOR.submit(() -> {
			while (true){
				try {
					//take()方法是阻塞方法，队列中有东西就把他给取出来
					VoucherOrder voucherOrder = orderTasks.take();
					currentProxy.handleSeckillVoucher(voucherOrder);
				} catch (Exception e){
					log.error("未知错误！");
				}
			}
		});
	}

	public VoucherOrderServiceImpl(ISeckillVoucherService seckillVoucherService, SnowflakeIdWorker snowflakeIdWorker, RedisLockUtils redisLockUtils, RedissonClient redissonClient, StringRedisTemplate stringRedisTemplate) {
		this.seckillVoucherService = seckillVoucherService;
		this.snowflakeIdWorker = snowflakeIdWorker;
		this.redisLockUtils = redisLockUtils;
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
		//1，使用synchronized
//		synchronized (UserHolder.getUser().getId().toString().intern()){ //调用intern方法获取字符串的规范形式
//			//获取Spring代理对象：因为直接调用的话是不管@Transactional的！
//			IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//			return proxy.realGetSeckillVoucher(voucherId, seckillVoucher);
//		}
		//2，使用自定义锁Utils
//		boolean lockedFlag = redisLockUtils.tryLock("seckillVoucher:" + UserHolder.getUser().getId(), 5);
//		if(!lockedFlag){
//			return Result.fail("抢购失败！");
//		}
//		try {
//			IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//			return proxy.realGetSeckillVoucher(voucherId, seckillVoucher);
//		} finally {
//			redisLockUtils.unlock("seckillVoucher:" + UserHolder.getUser().getId());
//		}
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
		Long result = stringRedisTemplate.execute(seckillVoucherLua, List.of(),
				UserHolder.getUser().getId().toString(), voucherId.toString());
		//其实这里应该给前端返回一些东西的！
		if(result == null){
			return Result.fail("抢购失败！内部出错！");
		} else if(result == 1) {
			return Result.fail("优惠券库存不足。");
		} else if(result == 2) {
			return Result.fail("该用户持该优惠券已达上限！");
		}
		long id = snowflakeIdWorker.nextId();
		VoucherOrder voucherOrder = new VoucherOrder();
		voucherOrder.setId(id);
		voucherOrder.setVoucherId(voucherId);
		voucherOrder.setUserId(UserHolder.getUser().getId());
		voucherOrder.setStatus(1); //未支付
		//异步下单
		currentProxy = (IVoucherOrderService) AopContext.currentProxy();
		orderTasks.add(voucherOrder);
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
