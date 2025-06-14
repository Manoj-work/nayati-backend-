package com.medhir.rest.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import com.medhir.rest.utils.GeneratedId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import org.springframework.data.annotation.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Getter
@Setter
@Document(collection = "expenses")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExpenseModel {
    @Id
    private String id;
    
    private String expenseId;
    
    @NotBlank(message = "Main head is required")
    @JsonProperty(required = true)
    private String mainHead;
    
    @NotBlank(message = "Expense head is required")
    @JsonProperty(required = true)
    private String expenseHead;
    
    @NotBlank(message = "Vendor is required")
    @JsonProperty(required = true)
    private String vendor;
    
    @NotBlank(message = "Initiated date is required")
    @JsonProperty(required = true)
    private String initiated;
    
    @NotBlank(message = "Status is required")
    @JsonProperty(required = true)
    private String status;
    
    @NotBlank(message = "Category is required")
    @JsonProperty(required = true)
    private String category = "";
    
    @JsonProperty(required = false)
    private String gstCredit = "";
    
    @JsonProperty(required = false)
    private String file = "";
    
    @NotNull(message = "Total amount is required")
    @JsonProperty(required = true)
    private Double totalAmount;
    
    @NotNull(message = "Amount requested is required")
    @JsonProperty(required = true)
    private Double amountRequested;
    
    @JsonProperty(required = false)
    private String comments = "";

    @JsonProperty(required = false)
    private String statusRemarks = "";

    @NotBlank(message = "Submitted by (employeeId) is required")
    @JsonProperty(required = true)
    @Indexed
    private String submittedBy = "";

    @NotBlank(message = "Company ID is required")
    @JsonProperty(required = true)
    @Indexed
    private String companyId = "";

    @JsonIgnore
    @Transient
    private GeneratedId generatedId;

    @PostConstruct
    public void init() {
        if (category == null) category = "";
        if (gstCredit == null) gstCredit = "";
        if (file == null) file = "";
        if (comments == null) comments = "";
    }

    public void setGeneratedId(GeneratedId generatedId) {
        this.generatedId = generatedId;
    }

    public void generateExpenseId() {
        if (this.expenseId == null && this.generatedId != null) {
            this.expenseId = generatedId.generateId("EXP", ExpenseModel.class, "expenseId");
        }
    }
} 