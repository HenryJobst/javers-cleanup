package io.github.henryjobst.javerscleanup.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tag")
public class Tag {

    @Id
    private String code;

    private String label;

    protected Tag() {}

    public Tag(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode()  { return code; }
    public String getLabel() { return label; }

    public void setLabel(String label) { this.label = label; }
}
