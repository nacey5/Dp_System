package com.dp_sys.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp_sys.dto.LoginFormDTO;
import com.dp_sys.dto.Result;
import com.dp_sys.dto.UserDTO;
import com.dp_sys.entity.User;
import com.dp_sys.mapper.UserMapper;
import com.dp_sys.service.IUserService;
import com.dp_sys.utils.RedisConstants;
import com.dp_sys.utils.RegexUtils;
import com.dp_sys.utils.SystemConstants;
import com.dp_sys.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Autowired
    private StringRedisTemplate redisTemplate;//string类型，可以不用再自行配置redis的配置文件，直接注入即可

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (!RegexUtils.isPhoneInvalid(phone)){
            //如果不符合，返回错误信息
            return Result.fail("手机输入格式不对");
        }
        //如果符合，生成验证码
        String checkCode = RandomUtil.randomNumbers(6);
        //保存验证码到session
        /*session.setAttribute("code",checkCode);*///取消这种保存方法，转存再redis中

        //保存验证码到redis中 //set key vale ex 120
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,checkCode,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码
        log.debug("发送短信验证成功,验证码{}",checkCode);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if (!RegexUtils.isPhoneInvalid(phone)){
            //如果不符合，返回错误信息
            return Result.fail("手机输入格式不对");
        }
        //一致，访问数据库查看用户数据
        User user = query().eq("phone", phone).one();
        //校验验证码和密码
        if (loginForm.getCode()==null||"".equals(loginForm.getCode())){
            //验证码不存在，校验密码
            if (loginForm.getPassword().equals(user.getPassword())){
                return Result.fail("请输入正确的密码");
            }
        }else {
            //密码不存在，校验验证码，从redis中取出
            String cacheCode = redisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+phone);
            //不一致，返回失败
            if (!cacheCode.equals(loginForm.getCode())) {
                return Result.fail("验证码错误");
            }
        }


        if (user == null) {
            //不存在创建新的用户保存在数据库
            user=createUserWithPhone(phone);
        }
        //存在，保存在redis中
        //t1--随机生成token，生成登陆令牌
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //t2--讲userDTO对象转换成hashMap存储
        //这个的作用是为了解决long无法转换成string的异常-java.base/java.lang.Long cannot be cast to java.base/java.lang.String
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                        CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //t3--存储数据到redis中
        redisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,userMap);
        //设置token的有效期
        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token, RedisConstants.LOGIN_USER_TTL,TimeUnit.SECONDS);
        //返回token
        return Result.ok(token);
    }

    @Override
    public Result queryUserById(Long id) {
        User user = getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        //获取当前的用户
        Long userId= UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=RedisConstants.USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis，setbit key offset 1
        redisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前的用户
        Long userId= UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=RedisConstants.USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截至到今天的所有签到的记录，返回的是一个十进制的数字
        List<Long> result = redisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands
                        .create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        if (result == null||result.isEmpty()) {
            //没有任何结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num==null||num==0){
            return Result.ok(0);
        }
        //循环遍历
        int count=0;
        while (true){
            //让这个数字与1做与运算，得到最后一个bit位
            if ((num&1)==0){//判断这个bit位是否位0
                // 如果为0 表示还没有签到，结束
                break;
            }else {//如果不为0，说明已经签到，计数器+1
                count++;
            }
            //把数字右移一位num>>1
            num>>>=1;
        }
        return Result.ok(count);
    }

    /**
     * 创建用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
