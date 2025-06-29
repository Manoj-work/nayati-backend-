package com.medhir.rest.controller;

import com.medhir.rest.model.LeadModel;
import com.medhir.rest.service.LeadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/leads")
public class LeadController {
    @Autowired
    private LeadService leadService;

    @PostMapping
    public ResponseEntity<LeadModel> createLead(@RequestBody LeadModel lead) {
        try {
            return ResponseEntity.ok(leadService.createLead(lead));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<LeadModel>> getAllLeads() {
        return ResponseEntity.ok(leadService.getAllLeads());
    }

    @GetMapping("/{leadId}")
    public ResponseEntity<LeadModel> getLeadById(@PathVariable String leadId) {
        Optional<LeadModel> lead = leadService.getLeadById(leadId);
        return lead.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{leadId}")
    public ResponseEntity<LeadModel> updateLead(@PathVariable String leadId, @RequestBody LeadModel lead) {
        try {
            return ResponseEntity.ok(leadService.updateLead(leadId, lead));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/{leadId}")
    public ResponseEntity<Void> deleteLead(@PathVariable String leadId) {
        leadService.deleteLead(leadId);
        return ResponseEntity.noContent().build();
    }
} 