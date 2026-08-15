package com.society.management.controller;

import com.society.management.service.JsonStorageService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/residents")
public class ResidentController {

    private final JsonStorageService store;
    private final PasswordEncoder encoder;

    public ResidentController(JsonStorageService store, PasswordEncoder encoder) {
        this.store = store;
        this.encoder = encoder;
    }

    @GetMapping
    public List<Map<String, Object>> all(HttpSession session) {
        boolean admin = "ADMIN".equals(resolveRole(session));
        List<Map<String, Object>> safeMembers = new ArrayList<>();

        for (Map<String, Object> storedMember : store.read("members.json")) {
            Map<String, Object> member = new LinkedHashMap<>(storedMember);
            member.remove("passwordHash");
            // Secret codes are recovery credentials and must never be sent to
            // members or secretaries, even if they call this endpoint directly.
            if (!admin) {
                member.remove("secretCode");
            }
            safeMembers.add(member);
        }
        return safeMembers;
    }

    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> request,
                                   HttpSession session) {

        String role = resolveRole(session);
        if (!"ADMIN".equals(role) && !"SECRETARY".equals(role)) {
            throw new IllegalStateException("Only Admin or Secretary can add members");
        }

        String phone = text(request, "phoneNumber");
        String flat = text(request, "flatNumber");
        String name = text(request, "memberName");
        String email = text(request, "email");

        // Support both names so older and newer frontends work.
        String password = text(request, "password");
        if (password.isBlank()) {
            password = text(request, "passwordHash");
        }

        if (phone.isBlank()) {
            throw new IllegalArgumentException("Phone Number is required");
        }
        if (flat.isBlank()) {
            throw new IllegalArgumentException("Flat Number is required");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("Member Name is required");
        }
        if (password.isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (password.length() < 6 || password.length() > 15) {
            throw new IllegalArgumentException("Password must be between 6 and 15 characters");
        }

        double contribution = numberValue(request.get("expectedAmount"));
        if (contribution <= 0) {
            contribution = numberValue(request.get("monthlyContribution"));
        }
        if (contribution <= 0) {
            throw new IllegalArgumentException("Monthly Contribution is required and must be greater than 0");
        }

        List<Map<String, Object>> members = store.read("members.json");

        for (Map<String, Object> member : members) {
            if (phone.equalsIgnoreCase(text(member, "phoneNumber"))) {
                throw new IllegalArgumentException("Phone Number already exists");
            }
            if (flat.equalsIgnoreCase(text(member, "flatNumber"))) {
                throw new IllegalArgumentException("Flat Number already exists");
            }
        }

        Map<String, Object> member = new LinkedHashMap<>();
        member.put("id", store.nextId("members.json"));
        member.put("memberId", unique4(members));
        member.put("secretCode", unique6(members));
        member.put("flatNumber", flat);
        member.put("memberName", name);
        member.put("phoneNumber", phone);
        member.put("email", email);
        member.put("expectedAmount", contribution);
        member.put("passwordHash", encoder.encode(password));

        members.add(member);
        store.write("members.json", members);

        // Never return passwordHash to the browser.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Member created successfully");
        response.put("id", member.get("id"));
        response.put("memberId", member.get("memberId"));
        if ("ADMIN".equals(role)) {
            response.put("secretCode", member.get("secretCode"));
        }
        response.put("flatNumber", member.get("flatNumber"));
        response.put("memberName", member.get("memberName"));
        response.put("phoneNumber", member.get("phoneNumber"));
        response.put("email", member.get("email"));
        response.put("expectedAmount", member.get("expectedAmount"));
        return response;
    }

    @PutMapping("/{id}")
    public Map<String, Object> edit(@PathVariable long id,
                                    @RequestBody Map<String, Object> request,
                                    HttpSession session) {

        String role = resolveRole(session);
        List<Map<String, Object>> members = store.read("members.json");
        Map<String, Object> member = find(members, id);

        if ("ADMIN".equals(role) || "SECRETARY".equals(role)) {
            String phone = text(request, "phoneNumber");
            String flat = text(request, "flatNumber");
            String name = text(request, "memberName");

            if (phone.isBlank() || flat.isBlank() || name.isBlank()) {
                throw new IllegalArgumentException("Phone Number, Flat Number and Member Name are required");
            }

            checkDuplicate(members, id, phone, flat);
            member.put("phoneNumber", phone);
            member.put("flatNumber", flat);
            member.put("memberName", name);
            member.put("email", text(request, "email"));
            member.put("expectedAmount", positiveContribution(request));
        } else {
            throw new IllegalStateException("Not allowed");
        }

        store.write("members.json", members);
        return member;
    }

    @PutMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable long id,
                                             @RequestBody Map<String, Object> request,
                                             HttpSession session) {
        String role = resolveRole(session);
        if (!"ADMIN".equals(role) && !"SECRETARY".equals(role)) {
            throw new IllegalStateException("Only Admin or Secretary can reset a member password");
        }

        String password = text(request, "password");
        if (password.length() < 6 || password.length() > 15) {
            throw new IllegalArgumentException("Password must be between 6 and 15 characters");
        }

        List<Map<String, Object>> members = store.read("members.json");
        Map<String, Object> member = find(members, id);
        member.put("passwordHash", encoder.encode(password));
        store.write("members.json", members);
        return Map.of("success", true, "message", "Member password reset successfully");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpSession session) {
        if (!"ADMIN".equals(resolveRole(session))) {
            throw new IllegalStateException("Only Admin can delete members");
        }

        List<Map<String, Object>> members = store.read("members.json");
        boolean removed = members.removeIf(x -> JsonStorageService.number(x.get("id")) == id);
        if (!removed) {
            throw new NoSuchElementException("Member not found");
        }

        store.write("members.json", members);
        return Map.of("success", true, "message", "Member deleted successfully");
    }

    private String resolveRole(HttpSession session) {
        Object role = session.getAttribute("role");
        if (role != null) {
            return String.valueOf(role).trim().toUpperCase(Locale.ROOT);
        }

        // Defensive fallback: recover the staff role from users.json.
        Object username = session.getAttribute("username");
        if (username != null) {
            String u = String.valueOf(username).trim();
            for (Map<String, Object> user : store.read("users.json")) {
                if (u.equals(text(user, "username"))) {
                    String storedRole = text(user, "role").toUpperCase(Locale.ROOT);
                    if ("ADMIN".equals(storedRole) || "SECRETARY".equals(storedRole)) {
                        session.setAttribute("role", storedRole);
                        return storedRole;
                    }
                }
            }
        }
        return "";
    }

    private Map<String, Object> find(List<Map<String, Object>> members, long id) {
        return members.stream()
                .filter(x -> JsonStorageService.number(x.get("id")) == id)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Member not found"));
    }

    private void checkDuplicate(List<Map<String, Object>> members, long id, String phone, String flat) {
        for (Map<String, Object> x : members) {
            long otherId = JsonStorageService.number(x.get("id"));
            if (otherId != id && phone.equalsIgnoreCase(text(x, "phoneNumber"))) {
                throw new IllegalArgumentException("Phone Number already exists");
            }
            if (otherId != id && flat.equalsIgnoreCase(text(x, "flatNumber"))) {
                throw new IllegalArgumentException("Flat Number already exists");
            }
        }
    }

    private double positiveContribution(Map<String, Object> request) {
        double value = numberValue(request.get("expectedAmount"));
        if (value <= 0) value = numberValue(request.get("monthlyContribution"));
        if (value <= 0) throw new IllegalArgumentException("Monthly Contribution must be greater than 0");
        return value;
    }

    private String unique4(List<Map<String, Object>> members) {
        Random random = new Random();
        while (true) {
            String value = String.valueOf(1000 + random.nextInt(9000));
            boolean exists = members.stream().anyMatch(x -> value.equals(text(x, "memberId")));
            if (!exists) return value;
        }
    }

    private String unique6(List<Map<String, Object>> members) {
        Random random = new Random();
        while (true) {
            String value = String.format("%06d", random.nextInt(1_000_000));
            boolean exists = members.stream().anyMatch(x -> value.equals(text(x, "secretCode")));
            if (!exists) return value;
        }
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double numberValue(Object value) {
        if (value == null) return 0;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }
}
