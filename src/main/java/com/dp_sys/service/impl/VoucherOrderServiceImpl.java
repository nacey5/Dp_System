package com.dp_sys.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.dp_sys.dto.Result;
import com.dp_sys.entity.VoucherOrder;
import com.dp_sys.mapper.VoucherOrderMapper;
import com.dp_sys.service.ISeckillVoucherService;
import com.dp_sys.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp_sys.utils.RedisIDWorker;
import com.dp_sys.utils.SimpleRedisLock;
import com.dp_sys.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIDWorker redisIDWorker;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private StringRedisTemplate redisTemplate;

    //创建一个单线程的线程池，如果已经拥有了秒杀业务，那么不用很快
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    private static volatile DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final  String QUEUE_NAME="stream-orders";
    static {
        if (SECKILL_SCRIPT==null){
            SECKILL_SCRIPT=new DefaultRedisScript<>();
            SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
            SECKILL_SCRIPT.setResultType(Long.class);
        }
    }
    /**
     * @PostConstruct 这个注解代表了spring初始之后开始执行这个方法
     */
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1获取消息队列中的订单信息,XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    //2判断订单信息是否为空
                    if (list==null||list.isEmpty()){
                        //2.1如果为null,说明没有信息，继续下一次循环
                        continue;
                    }
                    //3解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> recordValue = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true);
                    //4创建订单
                    CreateVoucherOrder(voucherOrder);
                    //5确认消息，XACK s1 g1 id
                    redisTemplate.opsForStream().acknowledge(QUEUE_NAME,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常");
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                //1获取pending-list中的订单信息,XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );
                //2判断订单信息是否为空
                if (list==null||list.isEmpty()){
                    //2.1如果为null,说明pending-list中没有错误信息，直接结束
                    break;
                }
                //3解析消息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> recordValue = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true);
                //4创建订单
                CreateVoucherOrder(voucherOrder);
                //5确认消息，XACK s1 g1 id
                redisTemplate.opsForStream().acknowledge(QUEUE_NAME,"g1",record.getId());

            } catch (Exception e) {
                log.error("处理pending-list订单异常");
                //为了避免频率过高，线程休息100ms
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interruptedException) {
                    interruptedException.printStackTrace();
                }

            }
        }
    }
   /* //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1获取队列中的订单信息
                    VoucherOrder order = orderTasks.take();
                    //2创建订单
                    CreateVoucherOrder(order);
                } catch (Exception e) {
                    log.error("处理订单异常");
                }
            }
        }
    }*/

    private void CreateVoucherOrder(VoucherOrder order) {
        //一人一单
        Long userId=order.getUserId();
        Long voucherId=order.getVoucherId();
        //创建锁对象
        SimpleRedisLock redisLock = new SimpleRedisLock(redisTemplate, "order:" + userId);
        //尝试获取锁
        boolean successGet = redisLock.tryLock(1200L);
        //判断
        if (!successGet){
            //获取锁失败,直接返回失败或者重试
            //在这个业务场景可以直接失败，这人开挂，无数请求过来，本来一人一单，这人开挂，所以直接返回失败，不用重试
            log.error("不允许重复下单");
            return ;
        }

            //查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断是否存在
            if (count>0){
                //用户已经购买过了
                log.error("不允许重复下单");
                return;
            }

            //扣减库存
            boolean success = seckillVoucherService.update().
                    setSql("stock=stock-1").
                    eq("voucher_id", voucherId).gt("stock", 0)//where id=? and stock=?
                    .update();
            if (!success){
                log.error("库存不足");
                return ;
            }

            save(order);
    }

    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIDWorker.nextId("order");
        //1执行lua脚本
        Long result = redisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),orderId.toString()
        );
        //将包装类转换
        int r=result.intValue();
        //2判断结果是否为0
        if (result!=0) {
            //2.1不为0，没有购买资格
            return Result.fail(r==1?"库存不足":"您已经购买过了");
        }
        //2.2为0，有购买资格，把下单信息保存到阻塞队列
        //返回订单id
        return Result.ok(orderId);
    }


    /*@Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        Long userId = UserHolder.getUser().getId();
        //1执行lua脚本
        Long result = redisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //将包装类转换
        int r=result.intValue();
        //2判断结果是否为0
        if (result!=0) {
            //2.1不为0，没有购买资格
            return Result.fail(r==1?"库存不足":"您已经购买过了");
        }
        //2.2为0，有购买资格，把下单信息保存到阻塞队列
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderID = redisIDWorker.nextId("order");
        voucherOrder.setId(orderID);
        //用户id
        voucherOrder.setUserId(userId);
        //代金卷id
        voucherOrder.setVoucherId(voucherId);
        //保存阻塞队列
        orderTasks.add(voucherOrder);
        //返回订单id
        return Result.ok(orderID);
    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否已经开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //已经结束
            return Result.fail("秒杀已经结束");
        }
        //判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        //返回订单id
        return getResult(voucherId);
    }*/

    /*@Transactional
    public Result getResult(Long voucherId){
        //一人一单
        Long userId=UserHolder.getUser().getId();
        //创建锁对象
        SimpleRedisLock redisLock = new SimpleRedisLock(redisTemplate, "order:" + userId);
        //尝试获取锁
        boolean successGet = redisLock.tryLock(1200L);
        //判断
        if (!successGet){
            //获取锁失败,直接返回失败或者重试
            //在这个业务场景可以直接失败，这人开挂，无数请求过来，本来一人一单，这人开挂，所以直接返回失败，不用重试
            return Result.fail("不允许重复下单");
        }

        try {
            //查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断是否存在
            if (count>0){
                //用户已经购买过了
                return Result.fail("该用户已经购买过");
            }

            //扣减库存
            boolean success = seckillVoucherService.update().
                    setSql("stock=stock-1").
                    eq("voucher_id", voucherId).gt("stock", 0)//where id=? and stock=?
                    .update();
            if (!success){
                return Result.fail("库存不足");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderID = redisIDWorker.nextId("order");
            voucherOrder.setId(orderID);
            //用户id
            voucherOrder.setUserId(userId);
            //代金卷id
            voucherOrder.setVoucherId(voucherId);

            save(voucherOrder);

            return Result.ok(orderID);
        }finally {
            //释放锁
            redisLock.unLock();
        }
    }*/

    /*@Transactional
    public Result getResult(Long voucherId) throws InterruptedException {
        //一人一单
        Long userId=UserHolder.getUser().getId();
        //创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        //尝试获取锁
        boolean successGet = redisLock.tryLock(1,10, TimeUnit.SECONDS);
        //判断
        if (!successGet){
            //获取锁失败,直接返回失败或者重试
            //在这个业务场景可以直接失败，这人开挂，无数请求过来，本来一人一单，这人开挂，所以直接返回失败，不用重试
            return Result.fail("不允许重复下单");
        }

        try {
            //查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断是否存在
            if (count>0){
                //用户已经购买过了
                return Result.fail("该用户已经购买过");
            }

            //扣减库存
            boolean success = seckillVoucherService.update().
                    setSql("stock=stock-1").
                    eq("voucher_id", voucherId).gt("stock", 0)//where id=? and stock=?
                    .update();
            if (!success){
                return Result.fail("库存不足");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderID = redisIDWorker.nextId("order");
            voucherOrder.setId(orderID);
            //用户id
            voucherOrder.setUserId(userId);
            //代金卷id
            voucherOrder.setVoucherId(voucherId);

            save(voucherOrder);

            return Result.ok(orderID);
        }finally {
            //释放锁
            redisLock.unlock();
        }
    }*/
}
