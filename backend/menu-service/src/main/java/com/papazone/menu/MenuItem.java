package com.papazone.menu;

import jakarta.persistence.*;

@Entity
@Table(name = "menu_items")
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(length = 1000)
    private String description;

    private String category;

    private double price;

    private String imageUrl;

    private boolean available = true;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String v) {
        category = v;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double v) {
        price = v;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String v) {
        imageUrl = v;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean v) {
        available = v;
    }
}