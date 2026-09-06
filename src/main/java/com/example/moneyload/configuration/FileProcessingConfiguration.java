package com.example.moneyload.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileProcessingProperties.class)
public class FileProcessingConfiguration { }
