package com.society.management.controller;

import com.society.management.service.JsonStorageService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private static final String EXPENSES_FILE = "expenses.json";
    private static final String ARCHIVE_FILE = "expense_archive.json";

    private final JsonStorageService store;

    public ExpenseController(JsonStorageService s) {
        store = s;
    }

    @GetMapping
    public List<Map<String, Object>> all() {
        return readRetainedExpenses();
    }

    @PostMapping
    public Map<String, Object> add(
            @RequestBody Map<String, Object> p,
            HttpSession s) {

        String role = String.valueOf(s.getAttribute("role"));
        if (!(role.equals("ADMIN") || role.equals("SECRETARY"))) {
            throw new RuntimeException("Not allowed");
        }

        if (str(p, "expenseDate").isBlank()
                || str(p, "category").isBlank()
                || num(p.get("amount")) <= 0) {
            throw new IllegalArgumentException(
                    "Expense date, category and amount are required");
        }

        LocalDate expenseDate = parseExpenseDate(str(p, "expenseDate"));

        if (expenseDate.isBefore(retentionStart())) {
            throw new IllegalArgumentException(
                    "Expenses older than 12 months cannot be added");
        }

        List<Map<String, Object>> es = readRetainedExpenses();

        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", store.nextId(EXPENSES_FILE));
        x.put("expenseDate", str(p, "expenseDate"));
        x.put("category", str(p, "category"));
        x.put("description", str(p, "description"));
        x.put("amount", num(p.get("amount")));
        x.put("paidTo", str(p, "paidTo"));

        es.add(x);
        store.write(EXPENSES_FILE, es);

        return x;
    }

    @PutMapping("/{id}")
    public Map<String, Object> edit(
            @PathVariable long id,
            @RequestBody Map<String, Object> p,
            HttpSession s) {

        String role = String.valueOf(s.getAttribute("role"));

        if (!(role.equals("ADMIN") || role.equals("SECRETARY"))) {
            throw new IllegalStateException("Not allowed");
        }

        List<Map<String, Object>> es = readRetainedExpenses();
        Map<String, Object> x = find(es, id);

        LocalDate newDate = parseExpenseDate(str(p, "expenseDate"));

        if (newDate.isBefore(retentionStart())) {
            throw new IllegalArgumentException(
                    "Expenses older than 12 months cannot be saved");
        }

        /*
         * Manual edit:
         * Only the selected expense is changed.
         * Nothing is added to the archived-expense total.
         */
        copy(x, p);

        store.write(EXPENSES_FILE, es);
        return x;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable long id,
            HttpSession s) {

        String role = String.valueOf(s.getAttribute("role"));

        if (!(role.equals("ADMIN") || role.equals("SECRETARY"))) {
            throw new IllegalStateException(
                    "Only Admin or Secretary can delete expenses");
        }

        List<Map<String, Object>> es = readRetainedExpenses();
        Map<String, Object> x = find(es, id);

        /*
         * Manual delete:
         * Do NOT add the deleted amount to the archived total.
         * Therefore the dashboard balance changes exactly as before.
         */
        es.remove(x);
        store.write(EXPENSES_FILE, es);

        return Map.of(
                "success", true,
                "message", "Expense deleted successfully");
    }

    private Map<String, Object> find(
            List<Map<String, Object>> es,
            long id) {

        return es.stream()
                .filter(x -> JsonStorageService.number(x.get("id")) == id)
                .findFirst()
                .orElseThrow(() ->
                        new RuntimeException("Expense not found"));
    }

    private void copy(
            Map<String, Object> x,
            Map<String, Object> p) {

        x.put("expenseDate", str(p, "expenseDate"));
        x.put("category", str(p, "category"));
        x.put("description", str(p, "description"));
        x.put("amount", num(p.get("amount")));
        x.put("paidTo", str(p, "paidTo"));
    }

    /*
     * Automatic retention cleanup:
     *
     * Expenses older than 12 months are removed from the active list.
     * Their amount is added ONCE to expense_archive.json.
     *
     * Manual delete/edit does not touch this archive.
     */
    public synchronized List<Map<String, Object>> readRetainedExpenses() {

        List<Map<String, Object>> expenses = store.read(EXPENSES_FILE);

        double archivedNow = 0.0;

        Iterator<Map<String, Object>> iterator = expenses.iterator();

        while (iterator.hasNext()) {
            Map<String, Object> expense = iterator.next();

            if (isOlderThanRetention(str(expense, "expenseDate"))) {
                archivedNow += num(expense.get("amount"));
                iterator.remove();
            }
        }

        if (archivedNow > 0) {
            addToArchivedExpenseTotal(archivedNow);
            store.write(EXPENSES_FILE, expenses);
        }

        return expenses;
    }

    private void addToArchivedExpenseTotal(double amount) {

        List<Map<String, Object>> archive = store.read(ARCHIVE_FILE);

        double currentTotal = 0.0;

        if (!archive.isEmpty()) {
            currentTotal = num(archive.get(0).get("total"));
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("total", currentTotal + amount);

        archive.clear();
        archive.add(record);

        store.write(ARCHIVE_FILE, archive);
    }

    private double getArchivedExpenseTotal() {

        List<Map<String, Object>> archive = store.read(ARCHIVE_FILE);

        if (archive.isEmpty()) {
            return 0.0;
        }

        return num(archive.get(0).get("total"));
    }

    /*
     * Kept public through a package-private accessor for DashboardController.
     * Dashboard uses the same persistent archive.
     */
    public double archivedExpenseTotal() {
        return getArchivedExpenseTotal();
    }

    private LocalDate retentionStart() {
        return LocalDate.now()
                .withDayOfMonth(1)
                .minusMonths(11);
    }

    private boolean isOlderThanRetention(String date) {
        try {
            return LocalDate.parse(date)
                    .isBefore(retentionStart());
        } catch (Exception e) {
            /*
             * Preserve the existing behavior for invalid old records:
             * they are treated as removable during automatic cleanup.
             */
            return true;
        }
    }

    private LocalDate parseExpenseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "A valid expense date is required");
        }
    }

    private static String str(
            Map<String, Object> m,
            String k) {

        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static double num(Object o) {
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
