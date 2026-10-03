package org.dyh.learnhub.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** operations 按顺序作用于上一步结果；只接受精确文本，不接受正则或模型重写的全文。 */
public record NoteEditRequest(
        @NotBlank @JsonProperty("expected_hash") String expectedHash,
        @NotEmpty @Size(max = 50) List<@Valid Operation> operations) {
    public record Operation(
            @NotBlank String action,
            String text,
            String value,
            Integer occurrence,
            Boolean all,
            String prefix,
            String suffix,
            String style,
            String placement,
            @JsonProperty("min_level") Integer minLevel,
            @JsonProperty("max_level") Integer maxLevel) {
    }
}
