package com.hmdp.interceptor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class UserInterceptor implements HandlerInterceptor {
	private final StringRedisTemplate stringRedisTemplate;
	private final ObjectMapper objectMapper;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		String token = request.getHeader("authorization");
		if(StrUtil.isBlank(token)){
			response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
			return false;
		}
		String json = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_USER_KEY + token);
		UserDTO userDTO = objectMapper.readValue(json, UserDTO.class);
		if(userDTO == null){
			response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
			return false;
		}
		//更新Token有效期
		stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, Duration.ofMinutes(30));
		UserHolder.saveUser(userDTO);
		return true;
	}

	@Override
	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
		UserHolder.removeUser();
	}
}
