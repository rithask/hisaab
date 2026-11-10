package com.hisaab.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Person {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** How the person's name shows up in UPI credit alerts, used to match repayments. */
    private String upiName;

    private String phone;

    @Column(columnDefinition = "text")
    private String notes;
}
