package com.urbantransport.ticketing.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerSummary(UUID id, String nit, String fullName, boolean generic, boolean hasAccount) {
}