package com.codereview.dto;

public record ProjectCreateReq(String name, String giteaUrl, String credential, Integer credentialType) {
}
