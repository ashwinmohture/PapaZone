
package com.papazone.menu;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;

@RestController
@RequestMapping("/menu")
@CrossOrigin(origins = "*")
public class MenuController {

    private final MenuRepository repo;

    public MenuController(MenuRepository r) {
        this.repo = r;
    }

    // Get all menu items
    @GetMapping
    public List<MenuItem> all() {

        if (repo.count() == 0) {
            seed();
        }

        return repo.findAll();
    }

    // Get menu item by ID
    @GetMapping("/{id}")
    public ResponseEntity<MenuItem> one(
            @PathVariable("id") Long id) {

        return repo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Create menu item
    @PostMapping
    public ResponseEntity<MenuItem> create(
            @RequestBody MenuItem m) {

        return ResponseEntity.status(201)
                .body(repo.save(m));
    }

    // Update menu item
    @PutMapping("/{id}")
    public ResponseEntity<MenuItem> update(
            @PathVariable("id") Long id,
            @RequestBody MenuItem m) {

        return repo.findById(id).map(x -> {

            x.setName(m.getName());
            x.setDescription(m.getDescription());
            x.setCategory(m.getCategory());
            x.setPrice(m.getPrice());
            x.setImageUrl(m.getImageUrl());
            x.setAvailable(m.isAvailable());

            return ResponseEntity.ok(repo.save(x));

        }).orElse(ResponseEntity.notFound().build());
    }

    // Delete menu item
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable("id") Long id) {

        if (!repo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        repo.deleteById(id);

        return ResponseEntity.noContent().build();
    }

    // Insert default menu items if database is empty
    private void seed() {

        String[][] rows = {
                {"Margherita Pizza", "Classic cheese pizza", "Pizza", "299"},
                {"Farmhouse Pizza", "Capsicum, onion and tomato", "Pizza", "449"},
                {"Pepperoni Pizza", "Pepperoni and mozzarella", "Pizza", "499"},
                {"Garlic Breadsticks", "Oven baked garlic bread", "Sides", "149"},
                {"Choco Lava Cake", "Warm chocolate dessert", "Dessert", "129"},
                {"Coke", "Chilled soft drink", "Beverages", "59"}
        };

        String imageUrl =
                "https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600";

        for (String[] a : rows) {

            MenuItem m = new MenuItem();

            m.setName(a[0]);
            m.setDescription(a[1]);
            m.setCategory(a[2]);
            m.setPrice(Double.parseDouble(a[3]));
            m.setImageUrl(imageUrl);

            repo.save(m);
        }
    }
}
