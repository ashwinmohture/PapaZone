
package com.papazone.customer;

import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

@RestController
@RequestMapping("/customers")
@CrossOrigin(origins = "*")
public class CustomerController {

    private final CustomerRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${INTERNAL_API_KEY:}")
    private String internalApiKey;

    public CustomerController(
            CustomerRepository repo,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record RegisterRequest(
            String name,
            String email,
            String phone,
            String password) {}

    public record LoginRequest(
            String email,
            String password) {}

    // CUSTOMER REGISTRATION
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest r) {

        if (r.name() == null || r.name().isBlank()
                || r.email() == null || r.email().isBlank()
                || r.password() == null || r.password().isBlank()) {

            return ResponseEntity.badRequest().body(
                    Map.of("message",
                            "name, email and password are required"));
        }

        String email = r.email().trim().toLowerCase();

        if (repo.findByEmail(email).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Email already registered"));
        }

        Customer customer = new Customer();

        customer.setName(r.name().trim());
        customer.setEmail(email);
        customer.setPhone(r.phone());

        // Encrypt password before saving
        customer.setPassword(passwordEncoder.encode(r.password()));

        // New registrations are always CUSTOMER
        customer.setRole("CUSTOMER");

        repo.save(customer);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of(
                        "id", customer.getId(),
                        "name", customer.getName(),
                        "email", customer.getEmail(),
                        "message", "Registration successful"
                )
        );
    }

    // CUSTOMER LOGIN
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest r) {

        if (r.email() == null || r.password() == null
                || r.email().isBlank() || r.password().isBlank()) {

            return ResponseEntity.badRequest().body(
                    Map.of("message", "Email and password are required"));
        }

        String email = r.email().trim().toLowerCase();

        return repo.findByEmail(email)
                .filter(customer -> {

                    String storedPassword = customer.getPassword();

                    if (storedPassword == null) {
                        return false;
                    }

                    // Support existing BCrypt passwords
                    if (storedPassword.startsWith("$2a$")
                            || storedPassword.startsWith("$2b$")
                            || storedPassword.startsWith("$2y$")) {

                        return passwordEncoder.matches(
                                r.password(), storedPassword);
                    }

                    // Temporary support for existing plain-text passwords
                    return Objects.equals(storedPassword, r.password());
                })
                .map(customer -> {

                    String storedPassword = customer.getPassword();

                    // Upgrade old plain-text password after successful login
                    if (!storedPassword.startsWith("$2a$")
                            && !storedPassword.startsWith("$2b$")
                            && !storedPassword.startsWith("$2y$")) {

                        customer.setPassword(
                                passwordEncoder.encode(r.password()));

                        repo.save(customer);
                    }

                    String role = customer.getRole() == null
                            ? "CUSTOMER"
                            : customer.getRole();

                    // Generate signed JWT
                    String token = jwtService.generateToken(
                            customer.getId(),
                            customer.getEmail(),
                            role
                    );

                    return ResponseEntity.ok(
                            Map.of(
                                    "token", token,
                                    "customerId", customer.getId(),
                                    "name", customer.getName(),
                                    "email", customer.getEmail(),
                                    "role", role
                            )
                    );
                })
                .orElseGet(() ->
                        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.of(
                                        "message",
                                        "Invalid email or password"
                                )));
    }

    // INTERNAL CUSTOMER LOOKUP (for trusted backend services only)
    @GetMapping("/internal/{id}")
    public ResponseEntity<?> getInternal(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Internal-Key", required = false) String suppliedKey) {

        if (internalApiKey == null || internalApiKey.isBlank()
                || suppliedKey == null || suppliedKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized internal request"));
        }

        boolean validKey = MessageDigest.isEqual(
                internalApiKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8));

        if (!validKey) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized internal request"));
        }

        return repo.findById(id)
                .<ResponseEntity<?>>map(customer -> ResponseEntity.ok(
                        Map.of(
                                "id", customer.getId(),
                                "name", customer.getName(),
                                "email", customer.getEmail()
                        )))
                .orElse(ResponseEntity.notFound().build());
    }

    // GET THE AUTHENTICATED CUSTOMER PROFILE (identity comes from the signed JWT)
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentCustomer(
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Bearer token is required"));
        }

        try {
            Claims claims = jwtService.validateToken(authorization.substring(7).trim());
            Object idClaim = claims.get("customerId");
            Long customerId = idClaim instanceof Number number
                    ? number.longValue()
                    : Long.valueOf(String.valueOf(
                            idClaim == null ? claims.getSubject() : idClaim));

            return repo.findById(customerId)
                    .<ResponseEntity<?>>map(customer -> ResponseEntity.ok(
                            Map.of(
                                    "id", customer.getId(),
                                    "name", customer.getName(),
                                    "email", customer.getEmail(),
                                    "phone", customer.getPhone() == null ? "" : customer.getPhone(),
                                    "role", customer.getRole() == null ? "CUSTOMER" : customer.getRole()
                            )))
                    .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("message", "Customer not found")));
        } catch (JwtException | IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Token is invalid or expired"));
        }
    }

    // GET CUSTOMER DETAILS
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") Long id) {

        return repo.findById(id)
                .<ResponseEntity<?>>map(customer ->
                        ResponseEntity.ok(
                                Map.of(
                                        "id", customer.getId(),
                                        "name", customer.getName(),
                                        "email", customer.getEmail(),
                                        "phone", customer.getPhone() == null
                                                ? ""
                                                : customer.getPhone()
                                )
                        ))
                .orElse(ResponseEntity.notFound().build());
    }
}
