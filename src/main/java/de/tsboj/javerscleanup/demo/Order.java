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

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "tag_code")
    private Tag tag;

    protected Order() {}

    public Order(String orderNumber, Customer customer, String status) {
        this(orderNumber, customer, status, null);
    }

    public Order(String orderNumber, Customer customer, String status, Tag tag) {
        this.orderNumber = orderNumber;
        this.customer = customer;
        this.status = status;
        this.tag = tag;
    }

    public Long getId()           { return id; }
    public String getOrderNumber(){ return orderNumber; }
    public Customer getCustomer() { return customer; }
    public String getStatus()     { return status; }
    public Tag getTag()           { return tag; }

    public void setStatus(String status)      { this.status = status; }
    public void setCustomer(Customer customer){ this.customer = customer; }
    public void setTag(Tag tag)               { this.tag = tag; }
}
