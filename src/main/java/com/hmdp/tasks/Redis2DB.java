package com.hmdp.tasks;

import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

//@Component
@Slf4j
public class Redis2DB {

	private final StringRedisTemplate stringRedisTemplate;
	private final IBlogService blogService;
	private final RedissonClient redissonClient;

	public Redis2DB(StringRedisTemplate stringRedisTemplate, IBlogService blogService, RedissonClient redissonClient) {
		this.stringRedisTemplate = stringRedisTemplate;
		this.blogService = blogService;
		this.redissonClient = redissonClient;
	}

//	@Scheduled(cron = "0 */30 * * * *") // every 30 minutes
	public void blogLike2DB(){
		log.info("同步数据库：blogLike2DB");
		RLock rLock = redissonClient.getLock(RedisConstants.LOCK_TASK_LIKE_REDIS_DB_KEY);
		if(rLock.tryLock()){
			try {
				Set<String> keys = stringRedisTemplate.keys(RedisConstants.BLOG_LIKED_KEY + "*");
				if(keys == null) return;
				keys.forEach(key -> {
					Long size = stringRedisTemplate.opsForZSet().size(key);
					String blogIdStr = key.substring(11); //blog:liked:(???)
					Long blogId = Long.valueOf(blogIdStr);
					blogService.lambdaUpdate()
							.eq(Blog::getId, blogId)
							.set(Blog::getLiked, size)
							.update();
				});
			} catch (Exception e){
				log.error("blogLike2DB：出现异常！");
			} finally {
				rLock.unlock();
			}
		}
	}
}
