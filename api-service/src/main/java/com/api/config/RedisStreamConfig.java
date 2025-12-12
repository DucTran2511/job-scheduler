package com.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisStreamConfig {
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> t = new RedisTemplate<>();
        t.setConnectionFactory(factory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        t.setKeySerializer(stringSerializer);
        t.setValueSerializer(stringSerializer);
        t.setHashKeySerializer(stringSerializer);
        t.setHashValueSerializer(stringSerializer);

        t.afterPropertiesSet();
        return t;
    }

    @Bean
    public StreamOperations<String, String, String> streamOps(RedisTemplate<String, String> redisTemplate) {
        return redisTemplate.opsForStream();
    }
}
