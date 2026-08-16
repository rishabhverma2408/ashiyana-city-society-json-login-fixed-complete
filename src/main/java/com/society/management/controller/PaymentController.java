package com.society.management.controller;

import com.society.management.service.JsonStorageService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import java.time.YearMonth;
import java.util.*;

@RestController @RequestMapping("/api/payments")
public class PaymentController {
    private final JsonStorageService store;
    public PaymentController(JsonStorageService s){store=s;}

    @GetMapping public List<Map<String,Object>> all(){return store.read("payments.json");}

    @PostMapping("/resident/{residentId}")
    public Map<String,Object> add(@PathVariable long residentId,@RequestBody Map<String,Object> p,HttpSession s){
        String role=String.valueOf(s.getAttribute("role")); if(!(role.equals("ADMIN")||role.equals("SECRETARY")))throw new RuntimeException("Not allowed");
        double amount=num(p.get("amount")); if(amount<=0)throw new RuntimeException("Payment amount is required");
        String date=str(p,"paymentDate"); if(date.isBlank())throw new RuntimeException("Payment date is required");

        // Capture a snapshot of the resident before saving the payment.
        // This keeps the historical member details available even if the resident
        // is deleted later.
        Map<String,Object> resident = findResident(residentId);
        if(resident == null)throw new RuntimeException("Member not found");

        String contributionMonth = str(p,"contributionMonth").trim();
        if (contributionMonth.isBlank()) {
            contributionMonth = date.length() >= 7 ? date.substring(0, 7) : "";
        }
        if (!contributionMonth.matches("\\d{4}-\\d{2}")) {
            throw new RuntimeException("A valid contribution month is required");
        }

        List<Map<String,Object>> ps=store.read("payments.json"); Map<String,Object> x=new LinkedHashMap<>();
        x.put("id",store.nextId("payments.json"));
        x.put("residentId",residentId);
        x.put("memberName",str(resident,"memberName"));
        x.put("flatNumber",str(resident,"flatNumber"));
        x.put("phoneNumber",str(resident,"phoneNumber"));
        x.put("email",str(resident,"email"));
        x.put("expectedAmount",num(resident.get("expectedAmount")));
        x.put("amount",amount);
        x.put("paymentDate",date);
        x.put("contributionMonth",contributionMonth);
        x.put("paymentMethod",str(p,"paymentMethod"));
        x.put("transactionId",str(p,"transactionId"));
        x.put("status","PAID");
        ps.add(x);store.write("payments.json",ps);return Map.of("success",true,"message","Payment marked as done","paymentId",x.get("id"),"status","PAID");
    }

    // =========================================================
    // MONTHLY PAYMENT STATUS FOR ALL CURRENT MEMBERS + HISTORICAL
    // PAYMENT RECORDS FOR MEMBERS WHO HAVE SINCE BEEN DELETED
    // =========================================================
    @GetMapping("/status")
    public List<Map<String, Object>> monthlyStatus(@RequestParam String month) {

        List<Map<String, Object>> members = store.read("members.json");
        List<Map<String, Object>> payments = store.read("payments.json");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Long> activeMemberIds = new HashSet<>();

        for (Map<String, Object> member : members) {

            long memberId = JsonStorageService.number(member.get("id"));
            activeMemberIds.add(memberId);

            double expected = getNumber(member.get("expectedAmount"));
            Map<String, Object> latestPayment = null;
            double paid = 0;

            for (Map<String, Object> payment : payments) {
                long paymentMemberId = JsonStorageService.number(payment.get("residentId"));
                String paymentMonth = getString(payment, "contributionMonth");

                if (paymentMemberId == memberId && month.equals(paymentMonth)) {
                    paid += getNumber(payment.get("amount"));
                    if (latestPayment == null || JsonStorageService.number(payment.get("id")) > JsonStorageService.number(latestPayment.get("id"))) {
                        latestPayment = payment;
                    }
                }
            }

            String status;
            if (paid <= 0) status = "PENDING";
            else if (paid >= expected) status = "PAID";
            else status = "PARTIAL";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", memberId);
            row.put("memberName", getString(member, "memberName"));
            row.put("phoneNumber", getString(member, "phoneNumber"));
            row.put("email", getString(member, "email"));
            row.put("flatNumber", getString(member, "flatNumber"));
            row.put("month", month);
            row.put("expectedAmount", expected);
            row.put("paidAmount", paid);
            row.put("status", status);

            if (latestPayment != null) {
                row.put("paymentId", latestPayment.get("id"));
                row.put("paymentDate", getString(latestPayment, "paymentDate"));
                row.put("paymentMethod", getString(latestPayment, "paymentMethod"));
                row.put("transactionId", getString(latestPayment, "transactionId"));
            } else {
                row.put("paymentId", null);
                row.put("paymentDate", "");
                row.put("paymentMethod", "");
                row.put("transactionId", "");
            }
            result.add(row);
        }

        // Preserve historical payment visibility after a resident is deleted.
        // These rows are built from the payment's saved snapshot fields.
        Map<Long, List<Map<String,Object>>> orphanPaymentsByResident = new LinkedHashMap<>();
        for (Map<String,Object> payment : payments) {
            long residentId = JsonStorageService.number(payment.get("residentId"));
            String paymentMonth = getString(payment, "contributionMonth");
            if (!activeMemberIds.contains(residentId) && month.equals(paymentMonth)) {
                orphanPaymentsByResident
                        .computeIfAbsent(residentId, k -> new ArrayList<>())
                        .add(payment);
            }
        }

        for (Map.Entry<Long, List<Map<String,Object>>> entry : orphanPaymentsByResident.entrySet()) {
            long residentId = entry.getKey();
            List<Map<String,Object>> residentPayments = entry.getValue();
            Map<String,Object> latestPayment = residentPayments.stream()
                    .max(Comparator.comparingLong(p -> JsonStorageService.number(p.get("id"))))
                    .orElse(null);

            double paid = residentPayments.stream()
                    .mapToDouble(p -> getNumber(p.get("amount")))
                    .sum();

            double expected = latestPayment == null
                    ? 0
                    : getNumber(latestPayment.get("expectedAmount"));

            Map<String,Object> row = new LinkedHashMap<>();
            row.put("memberId", residentId);
            row.put("memberName", getString(latestPayment, "memberName"));
            row.put("phoneNumber", getString(latestPayment, "phoneNumber"));
            row.put("email", getString(latestPayment, "email"));
            row.put("flatNumber", getString(latestPayment, "flatNumber"));
            row.put("month", month);
            row.put("expectedAmount", expected);
            row.put("paidAmount", paid);
            row.put("status", paid > 0 ? "PAID" : "PENDING");
            row.put("paymentId", latestPayment == null ? null : latestPayment.get("id"));
            row.put("paymentDate", latestPayment == null ? "" : getString(latestPayment, "paymentDate"));
            row.put("paymentMethod", latestPayment == null ? "" : getString(latestPayment, "paymentMethod"));
            row.put("transactionId", latestPayment == null ? "" : getString(latestPayment, "transactionId"));
            row.put("historicalMember", true);
            result.add(row);
        }

        return result;
    }

    @PutMapping("/{id}")
    public Map<String,Object> edit(@PathVariable long id,@RequestBody Map<String,Object> p,HttpSession s){
        String role=String.valueOf(s.getAttribute("role"));List<Map<String,Object>> ps=store.read("payments.json");Map<String,Object>x=find(ps,id);
        if(role.equals("ADMIN")){copy(x,p);}
        else if(role.equals("SECRETARY")){
            long rid=JsonStorageService.number(x.get("residentId"));List<Map<String,Object>> same=new ArrayList<>();for(Map<String,Object> q:ps)if(JsonStorageService.number(q.get("residentId"))==rid)same.add(q);
            same.sort(Comparator.comparingLong((Map<String,Object> q) -> JsonStorageService.number(q.get("id"))).reversed());if(same.isEmpty()||JsonStorageService.number(same.get(0).get("id"))!=id)throw new RuntimeException("Secretary can edit only the latest payment");copy(x,p);
        }else throw new RuntimeException("Not allowed");
        store.write("payments.json",ps);return Map.of("success",true,"message","Payment updated successfully");
    }

    @DeleteMapping("/{id}")
    public Map<String,Object> delete(@PathVariable long id,HttpSession s){
        String role=String.valueOf(s.getAttribute("role"));
        List<Map<String,Object>> ps=store.read("payments.json");
        Map<String,Object> payment=find(ps,id);
        if(!"ADMIN".equals(role)){
            if(!"SECRETARY".equals(role) || !isSecretaryDeleteMonth(str(payment,"contributionMonth"))){
                throw new IllegalStateException("Secretary can delete payments for the current or previous month only");
            }
        }
        ps.removeIf(x->JsonStorageService.number(x.get("id"))==id);
        store.write("payments.json",ps);
        return Map.of("success",true,"message","Payment deleted successfully");
    }

    private Map<String,Object> find(List<Map<String,Object>> ps,long id){return ps.stream().filter(x->JsonStorageService.number(x.get("id"))==id).findFirst().orElseThrow(()->new RuntimeException("Payment not found"));}

    private Map<String,Object> findResident(long id){
        return store.read("members.json").stream()
                .filter(x -> JsonStorageService.number(x.get("id")) == id)
                .findFirst()
                .orElse(null);
    }

    private boolean memberExists(long id){return findResident(id)!=null;}
    private boolean isSecretaryDeleteMonth(String month){
        try{
            YearMonth paymentMonth=YearMonth.parse(month);
            YearMonth currentMonth=YearMonth.now();
            return paymentMonth.equals(currentMonth)||paymentMonth.equals(currentMonth.minusMonths(1));
        }catch(Exception e){return false;}
    }

    private void copy(Map<String,Object>x,Map<String,Object>p){
        x.put("amount",num(p.get("amount")));
        x.put("paymentDate",str(p,"paymentDate"));
        x.put("contributionMonth",str(p,"contributionMonth"));
        x.put("paymentMethod",str(p,"paymentMethod"));
        x.put("transactionId",str(p,"transactionId"));
        x.put("status","PAID");
    }

    private static String getString(Map<String,Object> data,String key){
        if(data == null)return "";
        Object value=data.get(key);
        return value==null?"":String.valueOf(value).trim();
    }

    private static double getNumber(Object value){
        if(value==null)return 0;
        try{return Double.parseDouble(String.valueOf(value));}
        catch(NumberFormatException e){return 0;}
    }

    private static String str(Map<String,Object>m,String k){Object v=m.get(k);return v==null?"":String.valueOf(v);}
    private static double num(Object o){try{return Double.parseDouble(String.valueOf(o));}catch(Exception e){return 0;}}
}
