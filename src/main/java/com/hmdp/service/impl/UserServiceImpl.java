package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
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
		return Result.ok();
	}

	@Override
	public Result login(LoginFormDTO loginForm, HttpSession session) {
		//判断手机号
		if(RegexUtils.isPhoneInvalid(loginForm.getPhone())) return Result.fail("手机号格式错误！");
		//判断验证码
		String cachedCode = (String) session.getAttribute("code");
		if(!loginForm.getCode().equals(cachedCode)){
			return Result.fail("验证码错误！");
		}
		//查询用户
//		User user = getOne(new QueryWrapper<>(new User().setPhone(loginForm.getPhone())));
		User user = query().eq("phone", loginForm.getPhone()).one();
		if(user == null){
			user = createUser(loginForm);
		}
		//保存
		session.setAttribute("user", user);
		return Result.ok();
	}

	private User createUser(LoginFormDTO loginFormDTO) {
		User user = new User();
		BeanUtils.copyProperties(loginFormDTO, user);
		user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString( 10));
		save(user);
		log.info("创建新用户：{}", user);
		return user;
	}
}
