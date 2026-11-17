package com.devbook.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devbook.product-service")
record ProductServiceProperties(String url, Duration connectTimeout, Duration readTimeout) {}
