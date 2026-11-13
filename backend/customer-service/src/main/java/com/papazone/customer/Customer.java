package com.papazone.customer;
import jakarta.persistence.*;
@Entity @Table(name="customers") public class Customer {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private String name;
 @Column(nullable=false,unique=true) private String email;
 @Column(nullable = false)
 private String role = "CUSTOMER";
 private String phone; private String password;
 public Long getId(){return id;} public String getName(){return name;} public void setName(String v){name=v;} public String getEmail(){return email;} public void setEmail(String v){email=v;} public String getPhone(){return phone;} public void setPhone(String v){phone=v;} public String getPassword(){return password;} public void setPassword(String v){password=v;}
    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
