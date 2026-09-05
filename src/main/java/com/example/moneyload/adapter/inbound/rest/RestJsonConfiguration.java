package com.example.moneyload.adapter.inbound.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class RestJsonConfiguration {
    @Bean
    public JsonMapper restJsonMapper() {
        return JsonMapper.builder()
                .withCoercionConfig(String.class, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }
}
