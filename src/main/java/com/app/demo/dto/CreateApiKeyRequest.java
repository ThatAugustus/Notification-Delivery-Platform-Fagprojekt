
package com.app.demo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateApiKeyRequest {

    @NotBlank(message = "name is required (human-readable label, e.g. 'production' or 'staging')")
    private String name;
}
