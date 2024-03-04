package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

	private final StringRedisTemplate stringRedisTemplate;
	private final IUserService userService;
	private final UserMapper userMapper;
	private final RedissonClient redissonClient;

	public BlogServiceImpl(StringRedisTemplate stringRedisTemplate, IUserService userService, UserMapper userMapper, RedissonClient redissonClient) {
		this.stringRedisTemplate = stringRedisTemplate;
		this.userService = userService;
		this.userMapper = userMapper;
		this.redissonClient = redissonClient;
	}

	@Override
	public Result likeBlog(Long id) {
		if(UserHolder.getUser() == null) return Result.fail("用户未登录！");
		Long userId = UserHolder.getUser().getId();
		String key = RedisConstants.BLOG_LIKED_KEY + id;
		RLock rLock = redissonClient.getLock(RedisConstants.LOCK_LIKE_KEY + id);
		if(rLock.tryLock()){
			try {
				//已点赞就取消赞
				if(userLikedFlag(userId, id)){
					boolean updated = this.update()
							.setSql("liked = liked - 1").eq("id", id).update();
					if(updated) stringRedisTemplate.opsForZSet().remove(key, userId.toString());
				} else {
					boolean updated = this.update()
							.setSql("liked = liked + 1").eq("id", id).update();
					Long size = stringRedisTemplate.opsForZSet().size(key);
					if(size == null) size = 0L;
					if(updated) stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
				}
			} finally {
				rLock.unlock();
			}
			return Result.ok();
		}
		return Result.fail("点赞过于频繁！");
	}

	@Override
	public Result getBlog(Long id) {
		Blog blog = this.getById(id);
		if(blog == null) return Result.fail("博客不存在！");
		Long userId = blog.getUserId();
		User user = userService.getById(userId);
		blog.setName(user.getNickName());
		blog.setIcon(user.getIcon());
		if(UserHolder.getUser() != null)
			blog.setIsLike(userLikedFlag(UserHolder.getUser().getId(), id));
		return Result.ok(blog);
	}

	@Override
	public Result queryHotBlog(Integer current) {
		// 根据用户查询
		Page<Blog> page = this.query()
				.orderByDesc("liked")
				.page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
		// 获取当前页数据
		List<Blog> records = page.getRecords();
		// 查询用户
		records.forEach(blog -> {
			Long userId = blog.getUserId();
			Long id = blog.getId();
			User user = userService.getById(userId);
			blog.setName(user.getNickName());
			blog.setIcon(user.getIcon());
			if(UserHolder.getUser() != null)
				blog.setIsLike(userLikedFlag(UserHolder.getUser().getId(), id));
		});
		return Result.ok(records);
	}

	@Override
	public Result getLikesListByBlogId(Long id) {
		Set<String> userIdStr = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
		if(userIdStr == null) return Result.fail("博客不存在！");
		List<Long> userIds = userIdStr.stream().map(Long::valueOf).collect(Collectors.toList());
		List<User> users = userMapper.getUsersByIds(userIds);
		List<UserDTO> userDTOS = users.stream().map(
				user -> BeanUtil.copyProperties(user, UserDTO.class))
				.collect(Collectors.toList());
		return Result.ok(userDTOS);
	}

	private boolean userLikedFlag(Long userId, Long id){
		Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
		return score != null && score != 0;
	}
}
