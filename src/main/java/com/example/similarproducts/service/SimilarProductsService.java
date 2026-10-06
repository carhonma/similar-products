package com.example.similarproducts.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.example.similarproducts.exception.ProductNotFoundException;
import com.example.similarproducts.model.ProductDetail;

@Service
public class SimilarProductsService {

    private final RestClient downstream;
    private final Executor detailExecutor;

    @Value("${app.detail-overall-timeout-ms:1200}")
    private long detailOverallTimeoutMs;

    public SimilarProductsService(RestClient downstreamRestClient, Executor detailExecutor) {
        this.downstream = downstreamRestClient;
        this.detailExecutor = detailExecutor;
    }

    public List<ProductDetail> getSimilar(String productId) {
        List<String> ids = fetchSimilarIds(productId);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<Optional<ProductDetail>>> futures = ids.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> fetchDetail(id), detailExecutor))
                .toList();
        return futures.stream()
                .map(f -> {
                    try {
                        return f.get(detailOverallTimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        f.cancel(true);
                        return Optional.<ProductDetail>empty();
                    }
                })
                .flatMap(Optional::stream)
                .toList();
    }

    private List<String> fetchSimilarIds(String productId) {
        try {
            List<String> ids = downstream.get()
                    .uri("/product/{id}/similarids", productId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<String>>() {});
            return ids == null ? List.of() : ids;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductNotFoundException(productId);
        }
    }

    private Optional<ProductDetail> fetchDetail(String id) {
        try {
            ProductDetail d = downstream.get()
                    .uri("/product/{id}", id)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    })
                    .body(ProductDetail.class);
            if (d == null || d.id() == null) {
                return Optional.empty();
            }
            return Optional.of(d);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
