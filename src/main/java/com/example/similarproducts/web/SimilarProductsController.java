package com.example.similarproducts.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.example.similarproducts.model.ProductDetail;
import com.example.similarproducts.service.SimilarProductsService;

@RestController
public class SimilarProductsController {

    private final SimilarProductsService service;

    public SimilarProductsController(SimilarProductsService service) {
        this.service = service;
    }

    @GetMapping("/product/{productId}/similar")
    public ResponseEntity<List<ProductDetail>> getSimilar(@PathVariable String productId) {
        return ResponseEntity.ok(service.getSimilar(productId));
    }
}
