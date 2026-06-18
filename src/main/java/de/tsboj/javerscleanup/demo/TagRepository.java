package de.tsboj.javerscleanup.demo;

import org.javers.spring.annotation.JaversSpringDataAuditable;
import org.springframework.data.jpa.repository.JpaRepository;

@JaversSpringDataAuditable
public interface TagRepository extends JpaRepository<Tag, String> {}
