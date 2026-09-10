package com.group1.banking.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.group1.banking.dto.customer.CreateCustomerRequest;
import com.group1.banking.dto.customer.CustomerResponse;
import com.group1.banking.dto.customer.PatchCustomerRequest;
import com.group1.banking.security.AuthenticatedUser;
import com.group1.banking.service.CustomerService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CUSTOMER_CREATE') or hasRole('BANK_ADMINISTRATOR')")
    public ResponseEntity<CustomerResponse> createCustomer(@RequestBody @Valid CreateCustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.createCustomer(request));
    }

    @PatchMapping("/{customerId}")
    @PreAuthorize("(hasAuthority('CUSTOMER_UPDATE') and @ownershipService.canAccessCustomer(authentication, #customerId)) or hasRole('BANK_ADMINISTRATOR')")
    public ResponseEntity<CustomerResponse> updateCustomer(@PathVariable Long customerId,
                                                           @RequestBody @Valid PatchCustomerRequest request) {
        return ResponseEntity.ok(customerService.updateCustomer(customerId, request));
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("(hasAuthority('CUSTOMER_READ') and @ownershipService.canAccessCustomer(authentication, #customerId)) or hasRole('BANK_ADMINISTRATOR')")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable Long customerId) {
        return ResponseEntity.ok(customerService.getCustomer(customerId));
    }
    
    @GetMapping
    @PreAuthorize("hasRole('BANK_ADMINISTRATOR')")
    public ResponseEntity<List<CustomerResponse>> getAllCustomers() {
        return ResponseEntity.ok(customerService.getAllCustomers());
    }
    
    @DeleteMapping("/{customerId}")
    @PreAuthorize("hasRole('BANK_ADMINISTRATOR')")
    public Map<String, String> deleteCustomer(
            @PathVariable Long customerId) {
    	customerService.deleteCustomer(customerId);
        return Map.of("message", "Customer deleted successfully");
    }
}
