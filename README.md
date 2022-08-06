# Dp_system
这是一个基于springboot+mybatisPlus+redis实现的一个点评项目
# 点评项目

## 后端技术栈：springboot全家桶+mybatisPlus+redis+mysql+APIPost+lua脚本

## 前端技术栈：vue+nginx

---------------

## 简介

这是一个点评类的项目，项目主要是发布个人的探店笔记以及逛店和抢购优惠卷

--------

## 后端技术选型

- **springboot**因为其轻量级的启动以及其自动装配的便捷开发的属性是后端开发的首选。

- **redis**因为其数据存储的便捷性以及缓存数据读写的快速成为了与sql交互的首选，并且其支持分布式操作，在集合框架中有着非常好的优势
- **mybatis-Plus**传统的数据库操作封装框架，取消了之前使用逆向工具的使用，更加便捷和快速。

- **mysql**在传统开发中，mysql的比重比**oracle**小很多，但是因为其小型便捷和免费，所以在此次选择中选择了mysql进行开发

- **Redission**：使用的是Redission，使用Redsiion具体的原因是因为市面上的redis操作框架已经很成熟了，比如平常在使用redis的时候，当我们的向我们的redis发起连接的时候遭受到意外而连接不上的时候，我们使用手写Socket进行连接的时候可能无法解决这样的问题，而Redission底层拥有了自动重试的机制

- **lua脚本**：当前我们使用的技术选型中，因为redis不能保证业务的原子性，redis的命令执行是序列化的，会按照协议进行执行，并不能回滚，当执行过程中执行失败的时候，并不能将之前的执行过的命令进行回滚，所以需要使用lua脚本来保证其核心业务的原子性

  ------------------------------------------------------------

  ## 技术解析

### 简介

- 骨架：使用传统的mvc架构开发，满足解耦性，便于扩展。前后端分离，使用API-Post进行接口设计
- 登陆权限：使用用户id以及时间戳生成随机token保存在redis中，并进行ttl控制，具体参考interceptor.LoginInterceptor.java以及interceptor.RefreshTokenInterceptor.java
- 文件权限：使用springboot自动装配原理，进行对文件的过滤以及文件的放行，具体参考项目的MvcConfig.java
- 控制类设计：直接面向接口
- 信息类设计：我使用的是当某个方法需要使用到实体类的某些消息的时候，我会重新建立一个DTO类进行对相应属性的封装以免暴漏不必要的属性和配置
- 工具类设计：
  - 线程池--->主要针对redis可能出现的缓存击穿和缓存穿透进行一个设置和方法的调整
  - id生成器：类比雪花算法生成64位id
  - 使用ThreadLocal进行对当前线程用户的追踪
- 模式设计
  - ​	单例模式：因为在点评中可能涉及到秒杀业务，所以必须尽量保证一些全局检测变量不被多次创建，比如线程池和监控线程，所以需要使用单例模式(双重检测)
  - 责任链模式：因为在此次业务我并没有使用mq来保证消息不被丢失和正确消费，我采用的是责任链模式和AtomicInteger计数来保证消息的消费是单一线程保证完成的

-------------------------------------------------

## 项目截图
个人主页
<br/>
<div align=center><img src="https://github.com/nacey5/Dp_ystem/blob/master/image/DP_System_%E4%B8%AA%E4%BA%BA%E4%B8%BB%E9%A1%B5.png"></div>

发布个人笔记
<br/>
<div align=center><img src="https://github.com/nacey5/Dp_ystem/blob/master/image/DP_System_%E5%8F%91%E5%B8%83%E4%B8%AA%E4%BA%BA%E7%AC%94%E8%AE%B0.png"></div>

点赞互动
<br/>
<div align=center><img src="https://github.com/nacey5/Dp_ystem/blob/master/image/DP_System_%E7%82%B9%E8%B5%9E%E4%BA%92%E5%8A%A8.png"></div>


登陆
<br/>
<div align=center><img src="https://github.com/nacey5/Dp_ystem/blob/master/image/DP_System_%E7%99%BB%E9%99%86.png"></div>


资料编辑
<br/>
<div align=center><img src="https://github.com/nacey5/Dp_ystem/blob/master/image/DP_System_%E8%B5%84%E6%96%99%E7%BC%96%E8%BE%91.png"></div>


首页
<br/>
<div align=center><img src="https://github.com/nacey5/Dp_ystem/blob/master/image/DP_System_%E9%A6%96%E9%A1%B5.png"></div>
