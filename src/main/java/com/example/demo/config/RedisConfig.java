package com.example.demo.config;

import com.example.subscribe.Receiver;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Created by guiqi on 2017/8/3.
 */

@Configuration
@EnableCaching
public class RedisConfig {


    @Bean
    public KeyGenerator keyGenerator() {
        return new KeyGenerator() {
            @Override
            public Object generate(Object target, Method method, Object... params) {
                StringBuilder sb = new StringBuilder();
                sb.append(target.getClass().getName());
                sb.append(method.getName());
                for (Object obj : params) {
                    sb.append(obj.toString());
                }
                return sb.toString();
            }
        };
    }


    @SuppressWarnings("rawtypes")
    @Bean
    public CacheManager cacheManager(LettuceConnectionFactory connectionFactory) {
        RedisCacheConfiguration config =  RedisCacheConfiguration.defaultCacheConfig().computePrefixWith(cacheName-> "HGQ_"+cacheName).entryTtl(Duration.ofSeconds(60));
        RedisCacheManager cacheManager =  RedisCacheManager.builder(connectionFactory).cacheDefaults(config).build();
        return cacheManager;
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        Jackson2JsonRedisSerializer jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer(Object.class);
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(om.getPolymorphicTypeValidator(),ObjectMapper.DefaultTyping.NON_FINAL);
        jackson2JsonRedisSerializer.setObjectMapper(om);
        template.setValueSerializer(jackson2JsonRedisSerializer);
        template.afterPropertiesSet();

        return template;
    }


    //lettucePool已经被废弃
//    @Bean
//    LettucePool jedisPool(){
//        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
//        // 设置最大15个连接
//        jedisPoolConfig.setMaxTotal(400);
//        jedisPoolConfig.setMaxIdle(400);
//        jedisPoolConfig.setMinIdle(200);
//        jedisPoolConfig.setTestOnBorrow(true);
//        jedisPoolConfig.setTestOnReturn(false);
//        JedisPool pool = new JedisPool(jedisPoolConfig, "10.105.141.164",16379,1000,"redis123");
//        return pool;
//    }

    /**
     * jedisConnectionFactory是给redisTemplate实例提供的工厂类
     * @return
     */
    @Bean
    LettuceConnectionFactory connectionFactory() {

        GenericObjectPoolConfig genericPoolConfig = new GenericObjectPoolConfig();
        // 设置最大20个连接
        genericPoolConfig.setMaxTotal(20);
        genericPoolConfig.setMaxIdle(20);
        genericPoolConfig.setMinIdle(8);
        genericPoolConfig.setTestOnBorrow(true);
        genericPoolConfig.setTestOnReturn(false);

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder lpcf = (LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder) LettucePoolingClientConfiguration.builder();
        lpcf.poolConfig(genericPoolConfig);
        LettucePoolingClientConfiguration lettuceClientConfiguration = lpcf.build();

        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
        redisStandaloneConfiguration.setHostName("10.105.141.164");
        redisStandaloneConfiguration.setPort(16379);
        redisStandaloneConfiguration.setPassword("redis123");
        LettuceConnectionFactory lettuceConnectionFactory = new LettuceConnectionFactory(redisStandaloneConfiguration,lettuceClientConfiguration);
        //RedisConnectionFactory设置host-name，port，password都正确了，就没Connection failure occurred. Restarting subscription task 这个问题。
        return lettuceConnectionFactory;
    }


    @Bean
    RedisMessageListenerContainer container(LettuceConnectionFactory connectionFactory,
                                            MessageListenerAdapter listenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new PatternTopic("chat"));

        return container;
    }

    @Bean
    MessageListenerAdapter listenerAdapter(Receiver receiver) {
        //注册消息监听者
        return new MessageListenerAdapter(receiver, "receiveMessage");
    }

    @Bean
    Receiver receiver(CountDownLatch latch) {
        return new Receiver(latch);
    }

    @Bean
    CountDownLatch latch() {
        return new CountDownLatch(1);
    }


}
