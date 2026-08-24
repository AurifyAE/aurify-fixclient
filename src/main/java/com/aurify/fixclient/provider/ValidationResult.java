package com.aurify.fixclient.provider;

import lombok.Value;

import java.util.List;

@Value
public class ValidationResult {
    boolean valid;
    List<String> errors;

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult reject(String... reasons) {
        return new ValidationResult(false, List.of(reasons));
    }
}
