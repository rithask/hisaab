package com.hisaab.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@Setter
public class MfScheme {
    @Id
    private String code;

    private String name;

    private BigDecimal nav;

    private LocalDate navDate;
}
