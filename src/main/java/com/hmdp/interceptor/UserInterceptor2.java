package com.hmdp.interceptor;

import cn.hutool.http.HttpStatus;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class UserInterceptor2 implements HandlerInterceptor {
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		if(UserHolder.getUser() == null){
			response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
			return false;
		}
		return true;
	}
}
