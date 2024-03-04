package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

	private final IFollowService followService;

	public FollowController(IFollowService followService) {
		this.followService = followService;
	}

	@PutMapping("/{id}/{status}")
	public Result followOrUnfollow(@PathVariable Long id, @PathVariable Boolean status){
		return followService.followOrUnfollow(id, status);
	}

	@GetMapping("/or/not/{id}")
	public Result followOrNot(@PathVariable Long id){
		return followService.followOrNot(id);
	}

	@GetMapping("/common/{id}")
	public Result commonFollow(@PathVariable Long id){
		return followService.commonFollow(id);
	}
}
