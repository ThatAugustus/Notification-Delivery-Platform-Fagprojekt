
package com.app.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateTenantRequest {

    @NotBlank(message = "name is required")
    private String name;

    // Optional — some tenants only use webhooks and don't need a from-email.
    @Email(message = "defaultFromEmail must be a valid email address")
    private String defaultFromEmail;

    // Optional flags to control which channels are provisioned for the tenant
    private Boolean emailEnabled = true;
    private Boolean webhookEnabled = true;
}
