package de.tsboj.javerscleanup.demo;

import jakarta.persistence.Embeddable;

import java.time.LocalDate;

@Embeddable
public class ContractPeriod {

    private LocalDate startDate;
    private LocalDate endDate;

    protected ContractPeriod() {}

    public ContractPeriod(LocalDate startDate, LocalDate endDate) {
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate()   { return endDate; }

    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public void setEndDate(LocalDate endDate)     { this.endDate = endDate; }
}
