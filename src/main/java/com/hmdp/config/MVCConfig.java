package com.hmdp.config;

import com.hmdp.interceptor.UserInterceptor;
import com.hmdp.interceptor.UserInterceptor2;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class MVCConfig implements WebMvcConfigurer {
	private final UserInterceptor userInterceptor;
	private final UserInterceptor2 userInterceptor2;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(userInterceptor).order(1);
		registry.addInterceptor(userInterceptor2)
				.excludePathPatterns(
						"/user/code",
						"/user/login",
						"/blog/hot",
						"/shop/**",
						"/shop-type/**",
						"/upload/**",
						"/voucher/**"
				).order(2);
	}
}
