package com.hisaab.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class AppSetting {
    @Id
    private String key;

    private String value;

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
