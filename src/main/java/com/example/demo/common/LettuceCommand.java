package com.example.demo.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class LettuceCommand {

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


}
