package com.society.management.controller;

import com.society.management.service.JsonStorageService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final String EXPENSE_ARCHIVE_FILE =
            "expense_archive.json";

    private final JsonStorageService store;
    private final ExpenseController expenseController;

    public DashboardController(
            JsonStorageService s,
            ExpenseController expenseController) {
        store = s;
        this.expenseController = expenseController;
    }

    @GetMapping
    public Map<String, Object> get() {

        List<Map<String, Object>> ms =
                store.read("members.json");

        List<Map<String, Object>> ps =
                store.read("payments.json");

        /*
         * Reading expenses also performs the existing 12-month
         * automatic cleanup. Any automatically removed expense
         * amount is moved into expense_archive.json.
         */
        List<Map<String, Object>> es =
                expenseController.readRetainedExpenses();

        double collected = ps.stream()
                .mapToDouble(x ->
                        JsonStorageService.number(x.get("amount")))
                .sum();

        double activeExpenses = es.stream()
                .mapToDouble(x ->
                        JsonStorageService.number(x.get("amount")))
                .sum();

        /*
         * Automatically expired expenses remain included in the
         * financial total through this persistent archive.
         *
         * Manual delete/edit never changes this value.
         */
        double archivedExpenses =
                readArchivedExpenseTotal();

        double totalExpenses =
                activeExpenses + archivedExpenses;

        return Map.of(
                "totalFlats", ms.size(),
                "totalCollected", collected,
                "totalExpenses", totalExpenses,
                "currentBalance", collected - totalExpenses
        );
    }

    private double readArchivedExpenseTotal() {

        List<Map<String, Object>> archive =
                store.read(EXPENSE_ARCHIVE_FILE);

        if (archive.isEmpty()) {
            return 0.0;
        }

        return JsonStorageService.number(
                archive.get(0).get("total"));
    }
}
