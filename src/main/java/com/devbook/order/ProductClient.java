package com.devbook.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 상품 서비스 동기 호출. 실패는 예외 대신 Unavailable 로 돌려준다(재시도 없음 — 1단계). */
@Component
class ProductClient {

    private static final Logger log = LoggerFactory.getLogger(ProductClient.class);

    /** 주문에 필요한 필드만 읽는다. 상품 서비스가 필드를 추가해도 깨지지 않게 나머지는 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductSnapshot(long id, String title, long price) {}

    sealed interface FetchResult {
        record Found(Map<Long, ProductSnapshot> byId) implements FetchResult {}
        record Unavailable() implements FetchResult {}
    }

    private final RestClient restClient;

    ProductClient(ProductServiceProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(props.url())
                .requestFactory(requestFactory)
                .build();
    }

    FetchResult fetch(Collection<Long> ids) {
        String idsParam = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            List<ProductSnapshot> products = restClient.get()
                    .uri(uri -> uri.path("/api/products").queryParam("ids", idsParam).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ProductSnapshot>>() {});
            Map<Long, ProductSnapshot> byId = (products == null ? List.<ProductSnapshot>of() : products).stream()
                    .collect(Collectors.toUnmodifiableMap(ProductSnapshot::id, Function.identity()));
            return new FetchResult.Found(byId);
        } catch (RestClientException e) {
            log.warn("product-service call failed ids={}", idsParam, e);
            return new FetchResult.Unavailable();
        }
    }
}
