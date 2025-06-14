package com.medhir.rest.controller;

import com.medhir.rest.model.ExpenseModel;
import com.medhir.rest.service.ExpenseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import com.medhir.rest.dto.filter.UpdateExpenseStatusRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import java.util.Map;

@RestController
@RequestMapping("/expenses")
public class ExpenseController {
    @Autowired
    private ExpenseService expenseService;

    @PostMapping("/employee")
    public ResponseEntity<Map<String, Object>> createExpense(@Valid @RequestBody ExpenseModel expense) {
        ExpenseModel savedExpense = expenseService.createExpense(expense);
        return ResponseEntity.ok(Map.of(
                "message", "Expense created successfully!"
        ));
    }


    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ExpenseModel>> getAllExpenses() {
        return ResponseEntity.ok(expenseService.getAllExpenses());
    }

    @GetMapping(value = "/employee/{employeeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ExpenseModel>> getExpensesByEmployee(@PathVariable String employeeId) {
        return ResponseEntity.ok(expenseService.getExpensesByEmployee(employeeId));
    }

    

    @GetMapping(value = "/company/{companyId}/status/{status}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ExpenseModel>> getExpensesByCompanyAndStatus(
            @PathVariable String companyId,
            @PathVariable String status) {
        return ResponseEntity.ok(expenseService.getExpensesByCompanyAndStatus(companyId, status));
    }

    @GetMapping(value = "/manager/{managerId}/status/{status}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ExpenseModel>> getExpensesByManagerAndStatus(
            @PathVariable String managerId,
            @PathVariable String status) {
        return ResponseEntity.ok(expenseService.getExpensesByManagerAndStatus(managerId, status));
    }

    @GetMapping(value = "/{expenseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExpenseModel> getExpenseById(@PathVariable String expenseId) {
        Optional<ExpenseModel> expense = expenseService.getExpenseById(expenseId);
        return expense.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/employee/{expenseId}", 
                consumes = MediaType.APPLICATION_JSON_VALUE, 
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExpenseModel> updateExpense(
            @PathVariable String expenseId, 
            @RequestBody ExpenseModel expense) {
        try {
            return ResponseEntity.ok(expenseService.updateExpense(expenseId, expense));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping(value = "/updateStatus/{expenseId}", 
                consumes = MediaType.APPLICATION_JSON_VALUE, 
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExpenseModel> updateExpenseStatus(
            @PathVariable String expenseId,
            @Valid @RequestBody UpdateExpenseStatusRequest request,
            Authentication authentication) {
        try {
            // Verify HRADMIN role
            boolean isHrAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "HRADMIN".equals(role));

            if (!isHrAdmin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only HRADMIN can access this endpoint");
            }

            ExpenseModel updatedExpense = expenseService.updateExpenseStatusByHrAdmin(
                expenseId, 
                request.getStatus(),
                request.getRemarks()
            );
            return ResponseEntity.ok(updatedExpense);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping(value = "/manager/updateStatus/{expenseId}", 
                consumes = MediaType.APPLICATION_JSON_VALUE, 
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExpenseModel> updateExpenseStatusByManager(
            @PathVariable String expenseId,
            @Valid @RequestBody UpdateExpenseStatusRequest request,
            Authentication authentication) {
        try {
            // Verify MANAGER role
            boolean isManager = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "MANAGER".equals(role));

            if (!isManager) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only MANAGER can access this endpoint");
            }

            // Get current user's ID from security context details
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) authentication.getDetails();
            String currentUserId = (String) details.get("employeeId");

            ExpenseModel updatedExpense = expenseService.updateExpenseStatusByManager(
                expenseId, 
                request.getStatus(),
                request.getRemarks(),
                currentUserId
            );
            return ResponseEntity.ok(updatedExpense);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
} 