package com.datagov.app.insight;

import com.datagov.metadata.service.SensitivityService.SensitiveColumnView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InsightMaskingTest {

    @Test
    void masksSensitiveColumnSelectedDirectly() {
        List<Map<String, Object>> rows = List.of(Map.of("email", "a@b.com", "cnt", 3));
        InsightMasking.Masked masked = InsightMasking.mask(
                rows,
                List.of(new SuperSonicClient.Column("email", "email", "String"),
                        new SuperSonicClient.Column("cnt", "cnt", "Int64")),
                "SELECT email, count() AS cnt FROM users GROUP BY email",
                List.of(new SensitiveColumnView("email", "L2")));

        assertThat(masked.maskedColumns()).containsExactly("email");
        assertThat(masked.rows().get(0).get("email")).isEqualTo("***");
        assertThat(masked.rows().get(0).get("cnt")).isEqualTo(3);
    }

    @Test
    void masksSensitiveColumnBehindChineseAlias() {
        List<Map<String, Object>> rows = List.of(Map.of("_phone_", "13800000000"));
        InsightMasking.Masked masked = InsightMasking.mask(
                rows,
                List.of(new SuperSonicClient.Column("_phone_", "_phone_", "String")),
                "SELECT phone AS `_phone_` FROM users",
                List.of(new SensitiveColumnView("phone", "L3")));

        assertThat(masked.maskedColumns()).contains("_phone_");
        assertThat(masked.rows().get(0).get("_phone_")).isEqualTo("***");
    }

    @Test
    void doesNotMaskSubstringLookalike() {
        List<Map<String, Object>> rows = List.of(Map.of("username", "alice", "amount", 10));
        InsightMasking.Masked masked = InsightMasking.mask(
                rows,
                List.of(new SuperSonicClient.Column("username", "username", "String"),
                        new SuperSonicClient.Column("amount", "amount", "Decimal")),
                "SELECT username, amount FROM t",
                List.of(new SensitiveColumnView("name", "L2")));

        assertThat(masked.maskedColumns()).isEmpty();
        assertThat(masked.rows().get(0).get("username")).isEqualTo("alice");
    }
}
