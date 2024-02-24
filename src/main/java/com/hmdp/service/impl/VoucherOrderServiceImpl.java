package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

	private final ISeckillVoucherService seckillVoucherService;
	private final SnowflakeIdWorker snowflakeIdWorker;

	public VoucherOrderServiceImpl(ISeckillVoucherService seckillVoucherService, SnowflakeIdWorker snowflakeIdWorker) {
		this.seckillVoucherService = seckillVoucherService;
		this.snowflakeIdWorker = snowflakeIdWorker;
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
		//重点：
		synchronized (UserHolder.getUser().getId().toString().intern()){ //调用intern方法获取字符串的规范形式
			//获取Spring代理对象：因为直接调用的话是不管@Transactional的！
			IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
			return proxy.realGetSeckillVoucher(voucherId, seckillVoucher);
		}
	}

	@Transactional
	public Result realGetSeckillVoucher(Long voucherId, SeckillVoucher seckillVoucher){
		Integer count = lambdaQuery()
				.eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
				.eq(VoucherOrder::getVoucherId, voucherId)
				.count();
		if(count > 0) return Result.fail("该券已抢过上限！");
		//扣减库存
		boolean updated = seckillVoucherService.lambdaUpdate()
				.setSql("stock = stock - 1")
				.eq(SeckillVoucher::getVoucherId, voucherId)//乐观锁
				.gt(SeckillVoucher::getStock, 0)
				.update();
		if(!updated) return Result.fail("抢购失败！");
		long id = snowflakeIdWorker.nextId();
		VoucherOrder voucherOrder = new VoucherOrder();
		voucherOrder.setId(id);
		voucherOrder.setVoucherId(voucherId);
		voucherOrder.setUserId(UserHolder.getUser().getId());
		voucherOrder.setStatus(1); //未支付
		boolean saved = save(voucherOrder);
		if(!saved) return Result.fail("抢购失败！");
		return Result.ok(voucherOrder.getId());
	}
}
