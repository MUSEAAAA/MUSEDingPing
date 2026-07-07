package com.muse.dianping.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.muse.dianping.dto.Result;
import com.muse.dianping.dto.UserDTO;
import com.muse.dianping.entity.Blog;
import com.muse.dianping.entity.User;
import com.muse.dianping.mapper.BlogMapper;
import com.muse.dianping.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.muse.dianping.service.IUserService;
import com.muse.dianping.utils.SystemConstants;
import com.muse.dianping.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author MUSE
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;


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
            this.queryBlogUser(blog);
            this.isBlogLike(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("blog不存在");
        }
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLike(blog);
        return Result.ok(blog);
    }

    private void isBlogLike(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            blog.setIsLike(false);
            return;
        }
        String key = "blog:liked:" + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, user.getId().toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));

    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前的用户是否点赞
        String key = "blog:liked:" + id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isMember) ) {
        //3.如果未点赞，可以点赞
        //3.1数据库点赞数+1
            boolean isSuccess = update().setSql("liked =liked +1 ").eq("id", id).update();
            //这里是判断
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
            //3.2保存用户到redis的set集合

        }else {
            //4 如果已经已点赞，取消点赞
            //4.1数据库点赞谁-1
            boolean isSuccess = update().setSql("liked =liked - 1 ").eq("id", id).update();
            //4.2用户从redis的set集合中移除
            stringRedisTemplate.opsForSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
