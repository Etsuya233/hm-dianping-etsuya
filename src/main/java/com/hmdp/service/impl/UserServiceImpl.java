package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

	@Override
	public Result sendCode(String phone, HttpSession session) {
		//校验手机号
		if(RegexUtils.isPhoneInvalid(phone)){
			return Result.fail("手机号格式错误！");
		}
		//生成6位验证码
		String code = RandomUtil.randomNumbers(6);
		//将验证码传入Session
		session.setAttribute("code", code);
		log.info("验证码发送成功：{}", code);
		//TODO 真的发送验证码
		return Result.ok();
	}

}
