package com.tj.crypto.admin.dto;

import lombok.Data;

/** Credentials are accepted only in a JSON request body so proxies do not log them in the URL. */
@Data
public class LoginRequest {
    private String username;
    private String password;
}
