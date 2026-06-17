package de.tsboj.javerscleanup.demo;

import jakarta.persistence.*;

@Entity
@Table(name = "demo_order")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    private String status;

    protected Order() {}

    public Order(String orderNumber, Customer customer, String status) {
        this.orderNumber = orderNumber;
        this.customer = customer;
        this.status = status;
    }

    public Long getId()          { return id; }
    public String getOrderNumber(){ return orderNumber; }
    public Customer getCustomer() { return customer; }
    public String getStatus()    { return status; }

    public void setStatus(String status)     { this.status = status; }
    public void setCustomer(Customer customer){ this.customer = customer; }
}
