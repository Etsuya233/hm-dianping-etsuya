package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

	private final FollowMapper followMapper;
	private final IUserService userService;

	public FollowServiceImpl(FollowMapper followMapper, IUserService userService) {
		this.followMapper = followMapper;
		this.userService = userService;
	}

	@Override
	public Result followOrUnfollow(Long id, Boolean status) {
		if(status) {
			Follow follow = new Follow();
			follow.setUserId(UserHolder.getUser().getId());
			follow.setFollowUserId(id);
			save(follow);
		} else {
			remove(new QueryWrapper<Follow>()
					.eq("user_id", UserHolder.getUser().getId().toString())
					.eq("follow_user_id", id));
		}
		return Result.ok();
	}

	@Override
	public Result followOrNot(Long id) {
		Integer count = this.lambdaQuery()
				.eq(Follow::getUserId, UserHolder.getUser().getId())
				.eq(Follow::getFollowUserId, id)
				.count();
		return Result.ok(count != null && count != 0);
	}

	@Override
	public Result commonFollow(Long id) {
		Long userId = UserHolder.getUser().getId();
		List<Long> commonFollow = followMapper.getCommonFollow(userId, id);
		List<UserDTO> userDTOS = commonFollow.stream().map(a -> {
			User user = userService.getById(a);
			if(user != null) return BeanUtil.copyProperties(user, UserDTO.class);
			UserDTO userDTO = new UserDTO();
			userDTO.setNickName("用户已注销");
			return userDTO;
		}).collect(Collectors.toList());
		return Result.ok(userDTOS);
	}
}
;