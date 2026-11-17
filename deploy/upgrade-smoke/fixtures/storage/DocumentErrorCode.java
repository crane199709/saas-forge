package com.example.retained.document.service;
import io.saasforge.sdk.core.api.ErrorCode;
public enum DocumentErrorCode implements ErrorCode {
    NOT_FOUND;
    public String code() { return "document.notFound"; }
    public int httpStatus() { return 404; }
}
