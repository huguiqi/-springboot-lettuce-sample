


# SpringBoot + Redis模拟 10w 人的秒杀抢单！(lettuce实现)





本篇内容主要讲解的是redis分布式锁，这个在各大厂面试几乎都是必备的，下面结合模拟抢单的场景来使用她；本篇不涉及到的redis环境搭建，快速搭建个人测试环境，这里建议使用docker；本篇内容节点如下：



## Jedis的nx原生命令实现

> 原理

**setnx** 命令是redis的一条原生命令
 大意为 **set if not exists**， 在指定的key不存在的情况下，为key设置值
 使用如下

```shell
redis 127.0.0.1:6379> SETNX KEY_NAME VALUE
```


如何删除锁
模拟抢单动作(10w个人开抢)
jedis的nx生成锁
对于java中想操作redis，好的方式是使用jedis，首先pom中引入依赖：



```xml
<dependency>
  <groupId>redis.clients</groupId>
  <artifactId>jedis</artifactId>
</dependency>
```



因为我用的是：

```xml
<dependency>
   <groupId>org.springframework.boot</groupId>
   <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

它默认使用lettuce作为client实现redis连接，包含了lettuce客户端依赖包。



对于分布式锁的生成通常需要注意如下几个方面：



* 创建锁的策略：

  redis的普通key一般都允许覆盖，A用户set某个key后，B在set相同的key时同样能成功，如果是锁场景，那就无法知道到底是哪个用户set成功的；
  这里jedis的setnx方式为我们解决了这个问题，简单原理是：当A用户先set成功了，那B用户set的时候就返回失败，满足了某个时间点只允许一个用户拿到锁。

  

* 锁过期时间： 

  某个抢购场景时候，如果没有过期的概念，当A用户生成了锁，但是后面的流程被阻塞了一直无法释放锁，那其他用户此时获取锁就会一直失败，无法完成抢购的活动；
  当然正常情况一般都不会阻塞，A用户流程会正常释放锁；过期时间只是为了更有保障。

  

由于lettuce客户端本身并不提供`setnx`的接口实现，只能通过spring-data-redis的底层来实现`setnx`操作，下面来上段setnx操作的代码：

```java
    @Autowired
    private RedisTemplate redisTemplate;

    public boolean setnx(String key, String val) {
        try {
            int timemout = 60;
            boolean ret = redisTemplate.opsForValue().setIfAbsent(key,val,timemout, TimeUnit.SECONDS);
            return ret;
        } catch (Exception ex) {
            log.error("setnx error!!,key={},val={}",key,val,ex);
        }
        return false;
    }
```

为什么这段代码就实现了`setnx`的操作呢？这段代码主要看`setIfAbsent(args...)`方法,我们点进去源码看看:

```
public Boolean setIfAbsent(K key, V value, long timeout, TimeUnit unit) {
        byte[] rawKey = this.rawKey(key);
        byte[] rawValue = this.rawValue(value);
        Expiration expiration = Expiration.from(timeout, unit);
        return (Boolean)this.execute((connection) -> {
            return connection.set(rawKey, rawValue, expiration, SetOption.ifAbsent());
        }, true);
    }

```
源码分析：
1. 调用了这个AbstractOperations类的execute方法：
    
    ```
        @Nullable
       <T> T execute(RedisCallback<T> callback, boolean exposeConnection) {
           return this.template.execute(callback, exposeConnection);
       }
   ```

2. `RedisCallback` 中使用RedisConnection去执行redis
    
   在它实现的callback中，发现connection.set有个参数：`SetOption.ifAbsent()`

文档中看到`RedisStringCommands.SetOption`的api说明:

```
public static enum RedisStringCommands.SetOption
extends Enum<RedisStringCommands.SetOption>
SET command arguments for NX, XX.
Since:
1.7
Author:
Christoph Strobl
Enum Constant Summary
Enum Constants
Enum Constant and Description
SET_IF_ABSENT
NX
SET_IF_PRESENT
XX
UPSERT
Do not set any additional command argument.
```
![image-20220216155748958](https://tva1.sinaimg.cn/large/e6c9d24egy1gzga4ypkj3j21700mwq6i.jpg)

就是说`ifAbsent`方法就是`setnx`支持


setnx如果失败直接封装返回false即可，下面我们通过一个get方式的api来调用下这个setnx方法：



```java
@GetMapping("/setnx/{key}/{val}")
public boolean setnx(@PathVariable String key, @PathVariable String val) {
   return lettuceCommand.setnx(key, val);
}
```



访问如下测试url，正常来说第一次返回了true，第二次返回了false，由于第二次请求的时候redis的key已存在，所以无法set成功



![图片](https://tva1.sinaimg.cn/large/008i3skNly1gz68prkhjwj30il055q3m.jpg)





由上图能够看到只有一次set成功，并key具有一个有效时间，此时已到达了分布式锁的条件。





## 如何删除锁



上面是创建锁，同样的具有有效时间，但是我们不能完全依赖这个有效时间，场景如：有效时间设置1分钟，本身用户A获取锁后，没遇到什么特殊情况正常生成了抢购订单后，此时其他用户应该能正常下单了才对，但是由于有个1分钟后锁才能自动释放，那其他用户在这1分钟无法正常下单（因为锁还是A用户的），因此我们需要A用户操作完后，主动去解锁。

需要借助lua脚本去删除key，来达到解除锁的目的，而且必须是同一个用户才能解锁：



```java
    public int delnx(String key, String val) {
        try {
            //if redis.call('get','orderkey')=='1111' then return redis.call('del','orderkey') else return 0 end
            StringBuilder sbScript = new StringBuilder();
            sbScript.append("if redis.call('get',KEYS[1])").append("==ARGV[1]").
                    append(" then ").
                    append("    return redis.call('del',KEYS[1])").
                    append(" else ").
                    append("    return 0").
                    append(" end");

            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(sbScript.toString(),Long.class);
            Long ret = (Long) redisTemplate.execute(redisScript, Collections.singletonList(key),val);
            Integer result = 0;
            if (null != ret) {
                result = Integer.valueOf(ret.intValue());
            }
            return result;
        } catch (Exception ex) {
            log.error("delnx error!!,key={},value={}", key, val, ex);
        }
        return 0;
    }

```



这里不能像jedis的方式一样，必须通过设置变量取值来设置lua脚本：根据key和val判断其是否存在，如果存在就del；
其实个人认为通过redis的get方式获取val后，然后再比较value是否是当前持有锁的用户，如果是那最后再删除，效果其实相当；只不过直接通过eval执行脚本，这样避免多一次操作了redis而已，缩短了原子操作的间隔。(如有不同见解请留言探讨)；同样这里创建个get方式的api来测试：



```java
@GetMapping("/delnx/{key}/{val}")
public int delnx(@PathVariable String key, @PathVariable String val) {
   return lettuceCommand.delnx(key, val);
}
```





注意的是delnx时，需要传递创建锁时的value，因为通过et的value与delnx的value来判断是否是持有锁的操作请求，只有value一样才允许del；



## [模拟抢单动作（10w个人开抢）]

有了上面对分布式锁的粗略基础，我们模拟下10w人抢单的场景，其实就是一个并发操作请求而已，由于环境有限，只能如此测试；如下初始化10w个用户，并初始化库存，商品等信息，如下代码：

```
//总库存
    private long nKuCuen = 0;
    //商品key名字
    private String shangpingKey = "computer_key";
    //获取锁的超时时间 秒
    private int timeout = 30 * 1000;

    @GetMapping("/qiangdan")
    public List<String> qiangdan() {

        //抢到商品的用户
        List<String> shopUsers = new ArrayList<>();

        //构造很多用户
        List<String> users = new ArrayList<>(100000);
        IntStream.range(0, 100000).parallel().forEach(b -> {
            users.add("神牛-" + b);
        });

        //初始化库存
        nKuCuen = 10;

        //模拟开抢
        users.parallelStream().forEach(b -> {
            String shopUser = qiang(b);
            if (!StringUtils.isEmpty(shopUser)) {
                shopUsers.add(shopUser);
            }
        });

        return shopUsers;
    }
```

有了上面10w个不同用户，我们设定商品只有10个库存，然后通过并行流的方式来模拟抢购，如下抢购的实现：

```
/**
     * 模拟抢单动作
     *
     * @param b
     * @return
     */
    private String qiang(String b) {
        //用户开抢时间
        long startTime = System.currentTimeMillis();

        //未抢到的情况下，30秒内继续获取锁
        while ((startTime + timeout) >= System.currentTimeMillis()) {
            //商品是否剩余
            if (nKuCuen <= 0) {
                break;
            }
            if (lettuceCommand.setnx(shangpingKey, b)) {
                //用户b拿到锁
                logger.info("用户{}拿到锁...", b);
                try {
                    //商品是否剩余
                    if (nKuCuen <= 0) {
                        break;
                    }

                    //模拟生成订单耗时操作，方便查看：神牛-50 多次获取锁记录
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    //抢购成功，商品递减，记录用户
                    nKuCuen -= 1;

                    //抢单成功跳出
                    logger.info("用户{}抢单成功跳出...所剩库存：{}", b, nKuCuen);

                    return b + "抢单成功，所剩库存：" + nKuCuen;
                } finally {
                    logger.info("用户{}释放锁...", b);
                    //释放锁
                    lettuceCommand.delnx(shangpingKey, b);
                }
            } else {
                //用户b没拿到锁，在超时范围内继续请求锁，不需要处理
//                if (b.equals("神牛-50") || b.equals("神牛-69")) {
//                    logger.info("用户{}等待获取锁...", b);
//                }
            }
        }
        return "";
    }
```

这里实现的逻辑是：

1、parallelStream()：并行流模拟多用户抢购

2、(startTime + timeout) >= System.currentTimeMillis()：判断未抢成功的用户，timeout秒内继续获取锁

3、获取锁前和后都判断库存是否还足够

4、lettuceCommand.setnx(shangpingKey, b)：用户获取抢购锁

5、获取锁后并下单成功，最后释放锁：lettuceCommand.delnx(shangpingKey, b)

再来看下记录的日志结果：

![图片](https://tva1.sinaimg.cn/large/008i3skNly1gz68sbgcmcj30je0c7aba.jpg)



最终返回抢购成功的用户：



![图片](https://tva1.sinaimg.cn/large/008i3skNgy1gz68tch07vj30jl01qmxc.jpg)


## jedis和lettuce性能比较

抢单jedis实现耗时：
   第一次：10736ms
   第二次：10218ms
   第三次：10330ms
   第四次：10660ms
   第五次：10177ms
   平均：10424.2ms

lettuce实现耗时：
    第一次: 10186ms
	第二次：10240ms
	第三次：10307ms
	第四次：10518ms
	第五次：10187ms
    平均: 10287.6ms   

综合下来，lettuce的响应要稍微快些。



[实现代码地址](https://github.com/huguiqi/springboot-lettuce-sample)



[博客地址](https://clockcoder.com/2022/02/07/SpringBoot%20+%20Redis%E6%A8%A1%E6%8B%9F%2010w%20%E4%BA%BA%E7%9A%84%E7%A7%92%E6%9D%80%E6%8A%A2%E5%8D%95/)


## 集成lettuce遇到的问题

    
 
 [实现代码地址](https://github.com/huguiqi/springboot-lettuce-sample)
 

## jedis和lettuce、redisson的区别



| 客户端   | 底层实现                                                     | 优点                                                         | 缺点                                                         |
| -------- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| jedis    | sockets直连服务器                                            | 1. 老牌客户端，提供了比较全面的 Redis 命令的支持 <br />2. 用户基数大，社区支持较成熟 | 1. 使用阻塞的 I/O,调用同步,但事务和管道方式调用是异步的<br />2. 线程不安全,必须使用连接池 |
| lettuce  | netty nio异步框架实现                                        | 1. 支持同步异步通信模式<br />2. API 线程安全，如果不是执行阻塞和事务操作,多线程共享一个连接 | 1. 对redis高级使用支持不友好<br />2. 框架较新，社区支持没有jedis成熟 |
| redisson | 基于netty和redis 3.0以上版本协议实现的驻内存数据网格（In-Memory Data Grid） | 1. 支持同步异步通信模式<br />2. API 线程安全，如果不是执行阻塞和事务操作,多线程共享一个连接<br />3. 使用者对 Redis 的关注分离,提供很多分布式相关操作服务，例如，分布式锁，分布式集合，可通过Redis支持延迟队列等。<br />4. 用户基数同样很大,文档齐全 | 框架较重，学习成本较高                                       |



https://redisson.org/

https://github.com/redisson/redisson