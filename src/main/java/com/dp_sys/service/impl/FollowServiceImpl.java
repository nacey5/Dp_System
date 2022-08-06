package com.dp_sys.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dp_sys.dto.Result;
import com.dp_sys.dto.UserDTO;
import com.dp_sys.entity.Follow;
import com.dp_sys.mapper.FollowMapper;
import com.dp_sys.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp_sys.service.IUserService;
import com.dp_sys.utils.RedisConstants;
import com.dp_sys.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long followUserID, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        //1判断是关注还是取关
        if (isFollow) {
            //2关注，新增
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserID);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                redisTemplate.opsForSet().add(RedisConstants.FOLLOW_USER_KEY+userId,followUserID.toString());
            }
        }else {
            //3取关，删除数据 delete from tb_follow where user_id=? and follow_user_id=?
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserID));
            if (isSuccess) {
                redisTemplate.opsForSet().remove(RedisConstants.FOLLOW_USER_KEY+userId,followUserID.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserID) {
        Long userId = UserHolder.getUser().getId();
        //查询是否关注 select count(*) from tb_follow where user_id=? and follow_user_id=?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserID).count();
        //3判断
        return Result.ok(count>0);
    }

    @Override
    public Result commonFollow(Long id) {
        //获取当前用户
        Long id1 = UserHolder.getUser().getId();
        //求交集
        Set<String> intersect = redisTemplate.opsForSet().intersect(RedisConstants.FOLLOW_USER_KEY + id, RedisConstants.FOLLOW_USER_KEY + id1);
        if (intersect.isEmpty()||intersect==null){
            return Result.ok(Collections.emptyList());
        }
        //解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids).
                stream().
                map(user -> BeanUtil.copyProperties(user, UserDTO.class)).
                collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
