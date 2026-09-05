package com.example.moneyload.configuration;

import com.example.moneyload.domain.VelocityPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VelocityLimitProperties.class)
public class VelocityConfiguration {
    @Bean
    VelocityPolicy velocityPolicy(VelocityLimitProperties properties) {
        return properties.policy();
    }
}
