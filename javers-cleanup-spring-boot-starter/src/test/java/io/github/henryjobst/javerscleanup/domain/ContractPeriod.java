package io.github.henryjobst.javerscleanup.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.LocalDate;

@Embeddable
public class ContractPeriod {

    private LocalDate startDate;
    private LocalDate endDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "responsible_customer_id")
    private Customer responsibleCustomer;

    protected ContractPeriod() {}

    public ContractPeriod(LocalDate startDate, LocalDate endDate) {
        this(startDate, endDate, null);
    }

    public ContractPeriod(LocalDate startDate, LocalDate endDate, Customer responsibleCustomer) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.responsibleCustomer = responsibleCustomer;
    }

    public LocalDate getStartDate()              { return startDate; }
    public LocalDate getEndDate()                { return endDate; }
    public Customer getResponsibleCustomer()     { return responsibleCustomer; }

    public void setStartDate(LocalDate startDate)             { this.startDate = startDate; }
    public void setEndDate(LocalDate endDate)                 { this.endDate = endDate; }
    public void setResponsibleCustomer(Customer c)            { this.responsibleCustomer = c; }
}
