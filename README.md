


# SpringBoot + Redis模拟 10w 人的秒杀抢单！(lettuce实现)





本篇内容主要讲解的是redis分布式锁，这个在各大厂面试几乎都是必备的，下面结合模拟抢单的场景来使用她；本篇不涉及到的redis环境搭建，快速搭建个人测试环境，这里建议使用docker；本篇内容节点如下：



## Jedis的nx生成锁



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

它包含了jedis客户端依赖包。



对于分布式锁的生成通常需要注意如下几个方面：



* 创建锁的策略：

  redis的普通key一般都允许覆盖，A用户set某个key后，B在set相同的key时同样能成功，如果是锁场景，那就无法知道到底是哪个用户set成功的；
  这里jedis的setnx方式为我们解决了这个问题，简单原理是：当A用户先set成功了，那B用户set的时候就返回失败，满足了某个时间点只允许一个用户拿到锁。

  

* 锁过期时间： 

  某个抢购场景时候，如果没有过期的概念，当A用户生成了锁，但是后面的流程被阻塞了一直无法释放锁，那其他用户此时获取锁就会一直失败，无法完成抢购的活动；
  当然正常情况一般都不会阻塞，A用户流程会正常释放锁；过期时间只是为了更有保障。

  

下面来上段setnx操作的代码：

```java
    @Autowired
    private JedisConnectionFactory jedisConnectionFactory;

    private Jedis getJedis() {
        JedisConnection  jedisConnection = (JedisConnection)jedisConnectionFactory.getConnection();
        return jedisConnection.getNativeConnection();
    }

    public boolean setnx(String key, String val) {
        Jedis jedis = this.getJedis();
        try {
            if (jedis == null) {
                return false;
            }
            String ret = jedis.set(key, val, "NX", "PX", 1000 * 60);
            if (StringUtils.isEmpty(ret)){
                return false;
            }else
                return ret.equalsIgnoreCase("ok");
        } catch (Exception ex) {
            log.error("setnx error!!,key={},val={}",key,val,ex);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        return false;
    }
```





这里注意点在于jedis的set方法，其参数的说明如：

- NX：是否存在key，存在就不set成功
- PX：key过期时间单位设置为毫秒（EX：单位秒）

setnx如果失败直接封装返回false即可，下面我们通过一个get方式的api来调用下这个setnx方法：



```java
@GetMapping("/setnx/{key}/{val}")
public boolean setnx(@PathVariable String key, @PathVariable String val) {
   return jedisCommand.setnx(key, val);
}
```



访问如下测试url，正常来说第一次返回了true，第二次返回了false，由于第二次请求的时候redis的key已存在，所以无法set成功



![图片](https://tva1.sinaimg.cn/large/008i3skNly1gz68prkhjwj30il055q3m.jpg)





由上图能够看到只有一次set成功，并key具有一个有效时间，此时已到达了分布式锁的条件。





## 如何删除锁



上面是创建锁，同样的具有有效时间，但是我们不能完全依赖这个有效时间，场景如：有效时间设置1分钟，本身用户A获取锁后，没遇到什么特殊情况正常生成了抢购订单后，此时其他用户应该能正常下单了才对，但是由于有个1分钟后锁才能自动释放，那其他用户在这1分钟无法正常下单（因为锁还是A用户的），因此我们需要A用户操作完后，主动去解锁：





```java
	public int delnx(String key, String val) {
        Jedis jedis = this.getJedis();
        try {
            if (jedis == null) {
                return 0;
            }
        //if redis.call('get','orderkey')=='1111' then return redis.call('del','orderkey') else return 0 end
        StringBuilder sbScript = new StringBuilder();
        sbScript.append("if redis.call('get','").append(key).append("')").append("=='").append(val).append("'").
                append(" then ").
               append("    return redis.call('del','").append(key).append("')").
                append(" else ").
                append("    return 0").
                append(" end");

        return Integer.valueOf(jedis.eval(sbScript.toString()).toString());
    } catch (Exception ex) {
            log.error("delnx error!!,key={},value={}",key,val,ex);
    } finally {
        if (jedis != null) {
            jedis.close();
        }
   }
    return 0;
    }
```



这里也使用了jedis方式，直接执行lua脚本：根据val判断其是否存在，如果存在就del；
其实个人认为通过jedis的get方式获取val后，然后再比较value是否是当前持有锁的用户，如果是那最后再删除，效果其实相当；只不过直接通过eval执行脚本，这样避免多一次操作了redis而已，缩短了原子操作的间隔。(如有不同见解请留言探讨)；同样这里创建个get方式的api来测试：



```java
@GetMapping("/delnx/{key}/{val}")
public int delnx(@PathVariable String key, @PathVariable String val) {
   return jedisCommand.delnx(key, val);
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
            if (jedisCommand.setnx(shangpingKey, b)) {
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
                    jedisCommand.delnx(shangpingKey, b);
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

4、jedisCom.setnx(shangpingKey, b)：用户获取抢购锁

5、获取锁后并下单成功，最后释放锁：jedisCom.delnx(shangpingKey, b)

再来看下记录的日志结果：

![图片](https://tva1.sinaimg.cn/large/008i3skNly1gz68sbgcmcj30je0c7aba.jpg)



最终返回抢购成功的用户：



![图片](https://tva1.sinaimg.cn/large/008i3skNgy1gz68tch07vj30jl01qmxc.jpg)





[实现代码地址](https://github.com/huguiqi/springboot-jedis-sample)



[博客地址](https://clockcoder.com/2022/02/07/SpringBoot%20+%20Redis%E6%A8%A1%E6%8B%9F%2010w%20%E4%BA%BA%E7%9A%84%E7%A7%92%E6%9D%80%E6%8A%A2%E5%8D%95/)


## 集成jedis遇到的问题

在模拟测试10万人秒杀时，遇到一个jedis关闭时的连接池报错：

    ``` 
        JedisException:Could not return the resource to the pool 。。。IllegalStateException: Invalidated object not currently part of this pool
    ```

我 [参考此文章](https://blog.csdn.net/beguile/article/details/80614651) ，表示需要显示的将jedisPool进行资源回收处理，但是根据我的实践，并未起效果，所以根本原因并不是线程池未回收导致

所以继续找了下原因，感觉还是jedis里的连接状态与实际不一致导致的。

[参考这篇文章](https://mistray.github.io/2020/08/21/Jedis%E8%BF%9E%E6%8E%A5%E6%B1%A0%E7%AB%9F%E7%84%B6%E4%BC%9A%E8%B5%84%E6%BA%90%E6%B3%84%E9%9C%B2/)

最终原因终于找到了, 查看源码`Jedis 2.9.0` 的Jedis.class:

    public void close() {
        if (this.dataSource != null) {
            JedisPoolAbstract pool = this.dataSource;
            if (this.client.isBroken()) {
                pool.returnBrokenResource(this);
            } else {
                pool.returnResource(this);
            }
        } else {
            super.close();
        }

    }



通过断点进行发现, this.dataSource永远都是空的，也就是主逻辑永远进不来,结合上面参考的文章，结合我的问题，分析如下：

1. 因为jedis我之前是设置为静态共享的，所以当下个线程继续使用jedis时，有线程将socket关闭了，就会导致连接失败
2. 多线程问题导致jedis中的datasource被提前回收，另一线程发现没有了datasource，就把socket给关闭了
3. 未设置jedis客户端线程池参数, 最大可用线程池数量太少，有可能导致获取线程失败
4. 由于使用springboot 1.5.6.RELEASE 版本，而我的jedis是通过spring-boot-starter-data-redis引入的，它所使用的jedis版本是: `2.9.0`,而 `jedis 2.10.2` 以下版本都有此问题，属于bug

根据这四个问题，我是不是只要将jedis升级到2.10.2以后，还是使用静态共享的jedis变量，就可以解决这个问题了呢？
其实1，2是表象，最有可能的就是版本问题导致的，那么我就将jedis进行升级。

## 升级springboot和jedis版本

pom.xml:

    <parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>2.3.5.RELEASE</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
	
	<dependency>
    			<groupId>org.springframework.boot</groupId>
    			<artifactId>spring-boot-starter-data-redis</artifactId>
    			<exclusions>
    				<exclusion>
    					<groupId>io.lettuce</groupId>
    					<artifactId>lettuce-core</artifactId>
    				</exclusion>
    			</exclusions>
    </dependency>
    
    <dependency>
    			<groupId>redis.clients</groupId>
    			<artifactId>jedis</artifactId>
    			<version>3.3.0</version>
    </dependency>
	
* ps: 由于springboot 2.x 就默认将lettuce替换了jedis，为了防止包冲突，将lettuce exclude掉 *

然后再将RedisConfig.java进行改造：

```
    @Bean
    JedisConnectionFactory jedisConnectionFactory() {

        // 设置最大600个连接
        jedisPoolConfig.setMaxTotal(600);
        jedisPoolConfig.setMaxIdle(300);
        jedisPoolConfig.setTestOnBorrow(true);
        jedisPoolConfig.setTestOnReturn(false);

        JedisClientConfiguration.JedisPoolingClientConfigurationBuilder jpcf = (JedisClientConfiguration.JedisPoolingClientConfigurationBuilder) JedisClientConfiguration.builder();
        jpcf.poolConfig(jedisPoolConfig);
        JedisClientConfiguration jedisClientConfiguration = jpcf.build();

        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
        redisStandaloneConfiguration.setHostName("10.105.141.164");
        redisStandaloneConfiguration.setPort(16379);
        redisStandaloneConfiguration.setPassword("redis123");
        JedisConnectionFactory jedisConnectionFactory = new JedisConnectionFactory(redisStandaloneConfiguration,jedisClientConfiguration);

        return jedisConnectionFactory;
    }
```
    
JedisCommand.java中将jedis.set方法改成:

    SetParams setParams = SetParams.setParams().nx().px(1000 * 60);
    String ret = jedis.set(key, val, setParams);


Jedis变量设置为静态变量，作为线程共享：
    
    private static Jedis jedis;
    
     private Jedis getJedis() {
        if(jedis == null){
            JedisConnection  jedisConnection = (JedisConnection)jedisConnectionFactory.getConnection();
            this.jedis = jedisConnection.getNativeConnection();
            return this.jedis;
        }
       return jedis;     
     }


再次启动并执行后，发现并没有解决这个问题，那看来jedis的socket关闭后，无法重新进行连接了。
我看了jedis 3.3的close方法，和jedis 9.3.0的没有什么区别，并不是那位大哥说的那样。
我将jedis改成方法级别变量后，就不再出现这个问题了。

那还有种办法，是不是可以将jedis里的datasource生成，让它走主逻辑，就可以重新建立连接呢?
看源码，jedis里的datasource就是：Pool<Jedis> 类型，也就是个线程池，这说明我配置的线程池没起效？
原因：
由于我在JedisCommand类中使用的`JedisConnectionFactory` 类去获取Jedis实例的，而`JedisConnectionFactory`本身就不是线程池，是连接工厂类，所以我一直拿的是连接，并不是连接池

解决，RedisConfig.java中加入：
    
``` @Bean
    JedisPool jedisPool(){
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        // 设置最大60个连接
        jedisPoolConfig.setMaxTotal(600);
        jedisPoolConfig.setMaxIdle(300);
        jedisPoolConfig.setTestOnBorrow(true);
        jedisPoolConfig.setTestOnReturn(false);
        JedisPool pool = new JedisPool(jedisPoolConfig, "10.105.141.164",16379,1000,"redis123");
        return pool;
    }
```

JedisCommand.java修改：

```    private Jedis getJedis() {
            return jedisPool.getResource();
    }
```
将Jedis实例从池中获取，作为共享静态变量经测试还是会报错，至此，得出结论，不能将Jedis作为共享实例来使用，因为调用`jedis.close()`后，client的socket的就断了，而且在关闭socket之前已经将线程连接还给了线程池。

正确使用jedis：
    1. jedis即用即取,用完就关
    2. jedis实例不可作为共享变量来使用,调用close后，无法重连获取连接
    3. jedis实例其实就是JedisPool中的客户端连接，不应该为作共享变量来使用

总结下来解决方案：

  将jedis变量的作用域放到方法级别，方法执行完就回收掉
  本代码是使用的这种方式解决的，经过`jedis2.9.0`版本到`jedis 3.3.0`的升级与测试，最终得出结论：设置为方法级变量后，就不再出现那个报错了(即用即取，用完还掉，并关掉socket连接)。
    
 
 [实现代码地址](https://github.com/huguiqi/springboot-jedis-sample)
 



