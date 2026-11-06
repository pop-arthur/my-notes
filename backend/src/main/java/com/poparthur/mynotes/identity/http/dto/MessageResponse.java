package com.poparthur.mynotes.identity.http.dto;

public record MessageResponse(String message) {
    public static MessageResponse neutral() {
        return new MessageResponse("Если действие доступно для этого адреса, письмо будет отправлено.");
    }
}
