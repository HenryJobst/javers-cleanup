package de.tsboj.javerscleanup.demo;

import jakarta.persistence.*;

@Entity
@Table(name = "contract")
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Embedded
    private ContractPeriod period;

    protected Contract() {}

    public Contract(String title, ContractPeriod period) {
        this.title = title;
        this.period = period;
    }

    public Long getId()              { return id; }
    public String getTitle()         { return title; }
    public ContractPeriod getPeriod(){ return period; }

    public void setTitle(String title)           { this.title = title; }
    public void setPeriod(ContractPeriod period) { this.period = period; }
}
