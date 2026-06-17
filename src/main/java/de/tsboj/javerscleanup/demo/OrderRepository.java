package de.tsboj.javerscleanup.demo;

import org.javers.spring.annotation.JaversSpringDataAuditable;
import org.springframework.data.jpa.repository.JpaRepository;

@JaversSpringDataAuditable
public interface OrderRepository extends JpaRepository<Order, Long> {}
