package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.bcel.FakeAnnotation;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

	private final StringRedisTemplate stringRedisTemplate;

	private final ObjectMapper objectMapper;

	@Override
	public Result sendCode(String phone, HttpSession session) {
		//校验手机号
		if(RegexUtils.isPhoneInvalid(phone)){
			return Result.fail("手机号格式错误！");
		}
		//生成6位验证码
		String code = RandomUtil.randomNumbers(6);
		//将验证码存入Redis
		stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code);
		log.info("验证码发送成功：{}", code);
		return Result.ok();
	}

	@Override
	public Result login(LoginFormDTO loginForm, HttpSession session) {
		//判断手机号
		if(RegexUtils.isPhoneInvalid(loginForm.getPhone())) return Result.fail("手机号格式错误！");
		//判断验证码
		String cachedCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
		if(!loginForm.getCode().equals(cachedCode)){
			return Result.fail("验证码错误！");
		}
		//查询用户
		User user = query().eq("phone", loginForm.getPhone()).one();
		if(user == null){
			user = createUser(loginForm);
		}
		UserDTO userDTO = new UserDTO(); //脱敏
		BeanUtils.copyProperties(user, userDTO);
		//保存用户至Redis
		String json;
		try {
			json = objectMapper.writeValueAsString(userDTO);
		} catch (JsonProcessingException e) {
			return Result.fail("内部错误！");
		}
		String token = String.valueOf(UUID.randomUUID());
		stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_KEY + token, json, Duration.ofMinutes(30));
		//返回Token给前端
		return Result.ok(token);
	}

	@Override
	public Result getUserById(Long id) {
		User user = this.getById(id);
		if(user == null) return Result.fail("用户不存在！");
		return Result.ok(BeanUtil.copyProperties(user, UserDTO.class));
	}

	@Override
	public Result sign() {
		Long userId = UserHolder.getUser().getId();
		LocalDateTime now = LocalDateTime.now();
		String key = RedisConstants.USER_SIGN_KEY + userId + ":" + now.getYear() + ":" + now.getMonthValue();
		Boolean saved = stringRedisTemplate.opsForValue()
				.setBit(key, now.getDayOfMonth(), true);
		if(saved == null || saved) return Result.fail("用户签到失败！");
		return Result.ok();
	}

	@Override
	public Result getConsecutiveSignCount() {
		Long userId = UserHolder.getUser().getId();
		LocalDateTime now = LocalDateTime.now();
		String key = RedisConstants.USER_SIGN_KEY + userId + ":" + now.getYear() + ":" + now.getMonthValue();
		//获取签到bitmap对应的十进制数
		List<Long> res = stringRedisTemplate.opsForValue()
				.bitField(key, BitFieldSubCommands.create(
						BitFieldSubCommands.BitFieldGet.create(
								BitFieldSubCommands.BitFieldType.unsigned(now.getDayOfMonth() + 1),
								BitFieldSubCommands.Offset.offset(0))
				));
		if(res == null || res.isEmpty()) return Result.ok(0);
		//计算连续签到的日子
		Long binRes = res.get(0);
		if(binRes == null || binRes == 0) return Result.ok(0);
		int ret = 0;
		while(binRes > 0){
			if((binRes & 1L) == 1) ret++;
			else break;
			binRes /= 2;
		}
		return Result.ok(ret);
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
