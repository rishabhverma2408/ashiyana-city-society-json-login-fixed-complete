package com.society.management.controller;

import com.society.management.service.JsonStorageService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JsonStorageService store;
    private final PasswordEncoder enc;
    public AuthController(JsonStorageService s, PasswordEncoder e){store=s;enc=e;}

    @PostMapping("/login")
    public Map<String,Object> login(@RequestBody Map<String,String> m,HttpSession session){
        String u=m.getOrDefault("username","").trim();
        String p=m.getOrDefault("password","");

        for(Map<String,Object> x:store.read("users.json")){
            if(!u.equals(String.valueOf(x.get("username")))) continue;

            String storedHash=String.valueOf(x.getOrDefault("passwordHash",""));
            String storedPassword=String.valueOf(x.getOrDefault("password",""));
            boolean valid=!storedHash.isBlank() && enc.matches(p,storedHash);

            // Bootstrap support for the default Admin/Secretary accounts.
            // The plaintext value is immediately replaced by a BCrypt hash.
            if(!valid && !storedPassword.isBlank()){
                valid=p.equals(storedPassword);
                if(valid){
                    x.put("passwordHash",enc.encode(p));
                    x.remove("password");
                    store.write("users.json",store.read("users.json"));
                }
            }

            if(valid){
                session.setAttribute("username",u);
                session.setAttribute("role",x.get("role"));
                session.setAttribute("userId",JsonStorageService.number(x.get("id")));
                return Map.of("authenticated",true,"username",u,"role",x.get("role"));
            }
        }

        for(Map<String,Object> x:store.read("members.json")){
            if(u.equals(String.valueOf(x.get("phoneNumber"))) && enc.matches(p,String.valueOf(x.get("passwordHash")))){
                session.setAttribute("username",u);
                session.setAttribute("role","MEMBER");
                session.setAttribute("userId",JsonStorageService.number(x.get("id")));
                return Map.of("authenticated",true,"username",u,"role","MEMBER");
            }
        }

        throw new RuntimeException("Invalid credentials");
    }

    @PostMapping("/logout") public Map<String,String> logout(HttpSession s){s.invalidate();return Map.of("message","Logged out");}

    @GetMapping("/me")
    public Map<String,Object> me(HttpSession s){
        Object r=s.getAttribute("role");
        if(r==null)return Map.of("authenticated",false);
        return Map.of("authenticated",true,"username",s.getAttribute("username"),"role",r);
    }

    @PostMapping("/reset")
    public Map<String,String> reset(@RequestBody Map<String,String> m){
        String phone=m.get("phone"), current=m.get("currentPassword"), secret=m.get("secretCode"), nw=m.get("newPassword");
        if(nw==null||nw.length()<6||nw.length()>15)throw new RuntimeException("Password must be between 6 and 15 characters");
        List<Map<String,Object>> ms=store.read("members.json");
        for(Map<String,Object> x:ms){
            if(phone!=null && phone.equals(String.valueOf(x.get("phoneNumber")))){
                if(!enc.matches(current,String.valueOf(x.get("passwordHash"))) || !secret.equals(String.valueOf(x.get("secretCode"))))
                    throw new RuntimeException("Current password or secret code is incorrect");
                x.put("passwordHash",enc.encode(nw)); store.write("members.json",ms);
                return Map.of("message","Password changed successfully");
            }
        }
        throw new RuntimeException("Member not found");
    }
}