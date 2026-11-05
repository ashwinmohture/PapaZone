
package com.papazone.menu;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/menu")
@CrossOrigin(origins = "*")
public class MenuController {

    private final MenuRepository repo;

    public MenuController(MenuRepository repo) {
        System.out.println("test");
        this.repo = repo;
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
    public ResponseEntity<MenuItem> one(@PathVariable("id") Long id) {

        return repo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Create menu item
    @PostMapping
    public ResponseEntity<MenuItem> create(@RequestBody MenuItem menuItem) {

        return ResponseEntity.status(201)
                .body(repo.save(menuItem));
    }

    // Update menu item
    @PutMapping("/{id}")
    public ResponseEntity<MenuItem> update(
            @PathVariable("id") Long id,
            @RequestBody MenuItem menuItem) {

        return repo.findById(id).map(existingItem -> {

            existingItem.setName(menuItem.getName());
            existingItem.setDescription(menuItem.getDescription());
            existingItem.setCategory(menuItem.getCategory());
            existingItem.setPrice(menuItem.getPrice());
            existingItem.setImageUrl(menuItem.getImageUrl());
            existingItem.setAvailable(menuItem.isAvailable());

            return ResponseEntity.ok(repo.save(existingItem));

        }).orElse(ResponseEntity.notFound().build());
    }

    // Delete menu item
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {

        if (!repo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        repo.deleteById(id);

        return ResponseEntity.noContent().build();
    }

    // Insert default menu items if database is empty
    private void seed() {

        String[][] rows = {
                {
                        "Margherita Pizza",
                        "Classic cheese pizza",
                        "Pizza",
                        "299",
                        "https://images.unsplash.com/photo-1579751626657-72bc17010498?w=600"
                },
                {
                        "Farmhouse Pizza",
                        "Capsicum, onion and tomato",
                        "Pizza",
                        "449",
                        "https://images.unsplash.com/photo-1571407970349-bc81e7e96d47?w=600"
                },
                {
                        "Pepperoni Pizza",
                        "Pepperoni and mozzarella",
                        "Pizza",
                        "499",
                        "https://images.unsplash.com/photo-1628840042765-356cda07504e?w=600"
                },
                {
                        "Garlic Breadsticks",
                        "Oven baked garlic bread",
                        "Sides",
                        "149",
                        "https://images.unsplash.com/photo-1619531040576-f9416740661f?w=600"
                },
                {
                        "Choco Lava Cake",
                        "Warm chocolate dessert",
                        "Dessert",
                        "129",
                        "https://images.unsplash.com/photo-1606313564200-e75d5e30476c?w=600"
                },
                {
                        "Coke",
                        "Chilled soft drink",
                        "Beverages",
                        "59",
                        "https://images.unsplash.com/photo-1629203851122-3726ecdf080e?w=600"
                }
        };

        for (String[] item : rows) {

            MenuItem menuItem = new MenuItem();

            menuItem.setName(item[0]);
            menuItem.setDescription(item[1]);
            menuItem.setCategory(item[2]);
            menuItem.setPrice(Double.parseDouble(item[3]));
            menuItem.setImageUrl(item[4]);
            menuItem.setAvailable(true);

            repo.save(menuItem);
        }
    }
}
