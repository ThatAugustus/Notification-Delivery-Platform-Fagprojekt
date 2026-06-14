
package com.app.demo.dto;

import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateTenantRequest {

    // Both optional. Null means "leave unchanged."
    private String name;

    @Email(message = "defaultFromEmail must be a valid email address")
    private String defaultFromEmail;
}
