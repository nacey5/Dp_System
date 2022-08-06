package com.dp_sys.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dp_sys.dto.Result;
import com.dp_sys.dto.ScrollResult;
import com.dp_sys.dto.UserDTO;
import com.dp_sys.entity.Blog;
import com.dp_sys.entity.Follow;
import com.dp_sys.entity.User;
import com.dp_sys.mapper.BlogMapper;
import com.dp_sys.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp_sys.service.IFollowService;
import com.dp_sys.service.IUserService;
import com.dp_sys.utils.RedisConstants;
import com.dp_sys.utils.SystemConstants;
import com.dp_sys.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = query().eq("id", id).one();
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //获取用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            /**
             * 用户未登陆，无需查询是否点赞
             */
            return;
        }
        Long userId = user.getId();
        //判断当前用户是否已经点赞
        Double score = redisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(score==null?false:true);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否已经点赞
        Double score = redisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        //未点赞，可以点赞
        if (score==null) {
            ///数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            ///保存用户到redis的set集合
            if (isSuccess) {
                redisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY+id,userId.toString(),System.currentTimeMillis());
            }
            Result.ok();
        }else {
            //已经点赞，可以取消点赞
            ///数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            ///在redis的set集合中删除这个id
            if (isSuccess) {
                redisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY+id,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5的用户id
        Set<String> top5 = redisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //得到用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //查询用户列表order by field(id,5,1)
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("order by field(id,"+idStr+")").list()
                .stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)).
                collect(Collectors.toList());
        //返回用户
        return Result.ok(userDTOS);
    }

    /**
     * 根据用户查询博客
     * @param current
     * @param id
     * @return
     */
    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        Page<Blog> pages = query().eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        //获取当前页的数据
        List<Blog> records = pages.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增笔记失败");
        }
        //得到用笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //将笔记id发送给所有粉丝
        for (Follow follow : follows) {
            //1获取粉丝id
            Long userId = follow.getUserId();
            //2推送给粉丝
            redisTemplate.opsForZSet().add(RedisConstants.FEED_KEY+userId,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2查询收件箱 zrevrangebyscore key max min limit offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(
                        RedisConstants.FEED_KEY + userId,
                        0, max, offset, 2);
        //3非空判断
        if (typedTuples.isEmpty()||typedTuples==null) {
            return Result.ok();
        }
        //4解析数据，blogId，minTime(时间戳),offset
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //4.1获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
            //4.2获取分数(时间戳)
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                os++;
            }else {
                minTime=time;
                os=1;
            }
        }
        //4根据id查询blog]
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //查询blog有关的用户
            queryBlogUser(blog);
            //查询blog是否被点赞
            isBlogLiked(blog);
        }
        //5封装并返回
        ScrollResult result=new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(os);
        return Result.ok(result);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
