package com.teamflow.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordValidatorTest {
    private final PasswordValidator validator = new PasswordValidator();

    @Test
    void enforcesBcryptByteLimitForUnicodePasswords() {
        assertThat(validator.isValid("я".repeat(36), null)).isTrue();
        assertThat(validator.isValid("я".repeat(37), null)).isFalse();
        assertThat(validator.isValid("😀".repeat(18), null)).isTrue();
        assertThat(validator.isValid("😀".repeat(19), null)).isFalse();
        assertThat(validator.isValid("a".repeat(72), null)).isTrue();
        assertThat(validator.isValid("a".repeat(73), null)).isFalse();
    }

    @Test
    void requiresTenCharactersAndNonBlankValue() {
        assertThat(validator.isValid(null, null)).isFalse();
        assertThat(validator.isValid("short", null)).isFalse();
        assertThat(validator.isValid(" ".repeat(20), null)).isFalse();
        assertThat(validator.isValid("😀".repeat(5), null)).isFalse();
        assertThat(validator.isValid("long-password", null)).isTrue();
    }
}
