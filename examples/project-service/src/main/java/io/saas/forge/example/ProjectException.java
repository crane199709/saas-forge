package io.saas.forge.example;

class ProjectException extends RuntimeException {
    private final int status;
    private final String code;
    ProjectException(int status, String code, String detail) { super(detail); this.status = status; this.code = code; }
    int status() { return status; }
    String code() { return code; }
}
