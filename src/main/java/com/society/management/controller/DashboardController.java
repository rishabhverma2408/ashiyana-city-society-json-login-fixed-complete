package com.society.management.controller;
import com.society.management.service.JsonStorageService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/dashboard")
public class DashboardController{
 private final JsonStorageService store;public DashboardController(JsonStorageService s){store=s;}
 @GetMapping public Map<String,Object> get(){
  List<Map<String,Object>> ms=store.read("members.json"),ps=store.read("payments.json"),es=store.read("expenses.json");
  double collected=ps.stream().mapToDouble(x->JsonStorageService.number(x.get("amount"))).sum();
  double expenses=es.stream().mapToDouble(x->JsonStorageService.number(x.get("amount"))).sum();
  return Map.of("totalFlats",ms.size(),"totalCollected",collected,"totalExpenses",expenses,"currentBalance",collected-expenses);
 }
}