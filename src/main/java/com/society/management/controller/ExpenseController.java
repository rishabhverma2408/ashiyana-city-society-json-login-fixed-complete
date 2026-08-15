package com.society.management.controller;

import com.society.management.service.JsonStorageService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/api/expenses")
public class ExpenseController {
    private final JsonStorageService store;
    public ExpenseController(JsonStorageService s){store=s;}
    @GetMapping public List<Map<String,Object>> all(){return readRetainedExpenses();}
    @PostMapping public Map<String,Object> add(@RequestBody Map<String,Object> p,HttpSession s){
        String role=String.valueOf(s.getAttribute("role"));if(!(role.equals("ADMIN")||role.equals("SECRETARY")))throw new RuntimeException("Not allowed");
        if(str(p,"expenseDate").isBlank()||str(p,"category").isBlank()||num(p.get("amount"))<=0)throw new IllegalArgumentException("Expense date, category and amount are required");
        LocalDate expenseDate=parseExpenseDate(str(p,"expenseDate"));
        if(expenseDate.isBefore(retentionStart()))throw new IllegalArgumentException("Expenses older than 12 months cannot be added");
        List<Map<String,Object>> es=readRetainedExpenses();Map<String,Object>x=new LinkedHashMap<>();x.put("id",store.nextId("expenses.json"));x.put("expenseDate",str(p,"expenseDate"));x.put("category",str(p,"category"));x.put("description",str(p,"description"));x.put("amount",num(p.get("amount")));x.put("paidTo",str(p,"paidTo"));es.add(x);store.write("expenses.json",es);return x;
    }
    @PutMapping("/{id}") public Map<String,Object> edit(@PathVariable long id,@RequestBody Map<String,Object> p,HttpSession s){
        String role=String.valueOf(s.getAttribute("role"));List<Map<String,Object>> es=readRetainedExpenses();Map<String,Object>x=find(es,id);
        if(!(role.equals("ADMIN")||role.equals("SECRETARY")))throw new IllegalStateException("Not allowed");
        LocalDate newDate=parseExpenseDate(str(p,"expenseDate"));
        if(newDate.isBefore(retentionStart()))throw new IllegalArgumentException("Expenses older than 12 months cannot be saved");
        copy(x,p);
        store.write("expenses.json",es);return x;
    }
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable long id,HttpSession s){String role=String.valueOf(s.getAttribute("role"));if(!(role.equals("ADMIN")||role.equals("SECRETARY")))throw new IllegalStateException("Only Admin or Secretary can delete expenses");List<Map<String,Object>> es=readRetainedExpenses();Map<String,Object>x=find(es,id);es.remove(x);store.write("expenses.json",es);return Map.of("success",true,"message","Expense deleted successfully");}
    private Map<String,Object> find(List<Map<String,Object>>es,long id){return es.stream().filter(x->JsonStorageService.number(x.get("id"))==id).findFirst().orElseThrow(()->new RuntimeException("Expense not found"));}
    private void copy(Map<String,Object>x,Map<String,Object>p){x.put("expenseDate",str(p,"expenseDate"));x.put("category",str(p,"category"));x.put("description",str(p,"description"));x.put("amount",num(p.get("amount")));x.put("paidTo",str(p,"paidTo"));}
    private List<Map<String,Object>> readRetainedExpenses(){List<Map<String,Object>> expenses=store.read("expenses.json");if(expenses.removeIf(x->isOlderThanRetention(str(x,"expenseDate"))))store.write("expenses.json",expenses);return expenses;}
    private LocalDate retentionStart(){return LocalDate.now().withDayOfMonth(1).minusMonths(11);}
    private boolean isOlderThanRetention(String date){try{return LocalDate.parse(date).isBefore(retentionStart());}catch(Exception e){return true;}}
    private LocalDate parseExpenseDate(String date){try{return LocalDate.parse(date);}catch(Exception e){throw new IllegalArgumentException("A valid expense date is required");}}
    private static String str(Map<String,Object>m,String k){Object v=m.get(k);return v==null?"":String.valueOf(v);}private static double num(Object o){try{return Double.parseDouble(String.valueOf(o));}catch(Exception e){return 0;}}
}
