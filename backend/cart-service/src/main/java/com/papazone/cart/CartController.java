
package com.papazone.cart;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;

import java.util.*;

@RestController
@RequestMapping("/cart")
@CrossOrigin(origins = "*")
public class CartController {

    private final CartRepository repo;
    private final RestClient http = RestClient.create();

    @Value("${MENU_SERVICE_URL:http://localhost:8082}")
    private String menuUrl;

    public CartController(CartRepository r) {
        repo = r;
    }

    public record AddRequest(Long menuItemId, Integer quantity) {}

    @GetMapping("/{customerId}")
    public Map<String, Object> get(
            @PathVariable("customerId") Long customerId) {

        List<CartItem> items = repo.findByCustomerId(customerId);

        double total = items.stream()
                .mapToDouble(i -> i.getUnitPrice() * i.getQuantity())
                .sum();

        return Map.of(
                "items", items,
                "total", total,
                "itemCount", items.stream()
                        .mapToInt(CartItem::getQuantity)
                        .sum()
        );
    }

    @PostMapping("/{customerId}/items")
    public ResponseEntity<?> add(
            @PathVariable("customerId") Long customerId,
            @RequestBody AddRequest r) {

        if (r.quantity() == null || r.quantity() < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "quantity must be at least 1"));
        }

        try {
            Map m = http.get()
                    .uri(menuUrl + "/menu/" + r.menuItemId())
                    .retrieve()
                    .body(Map.class);

            CartItem i = new CartItem();
            i.setCustomerId(customerId);
            i.setMenuItemId(r.menuItemId());
            i.setName((String) m.get("name"));
            i.setUnitPrice(Double.parseDouble(m.get("price").toString()));
            i.setQuantity(r.quantity());

            return ResponseEntity.status(201).body(repo.save(i));

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message",
                            "Menu item unavailable; verify menu service is running"));
        }
    }

    @PatchMapping("/items/{itemId}")
    public ResponseEntity<?> update(
            @PathVariable("itemId") Long itemId,
            @RequestBody Map<String, Integer> b) {

        return repo.findById(itemId).map(i -> {
            int q = b.getOrDefault("quantity", 1);

            if (q < 1) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "quantity must be at least 1"));
            }

            i.setQuantity(q);
            return ResponseEntity.ok(repo.save(i));

        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{customerId}/items/{itemId}")
    public ResponseEntity<?> remove(
            @PathVariable("customerId") Long customerId,
            @PathVariable("itemId") Long itemId) {

        return repo.findById(itemId)
                .filter(i -> i.getCustomerId().equals(customerId))
                .map(i -> {
                    repo.delete(i);
                    return ResponseEntity.noContent().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{customerId}")
    public void clear(
            @PathVariable("customerId") Long customerId) {

        repo.deleteAll(repo.findByCustomerId(customerId));
    }
}
