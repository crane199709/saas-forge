package io.saas.forge.remotedelivery;

final class ManifestException extends RuntimeException {
    private final int status;
    private final String code;
    ManifestException(int status, String code) { super(code); this.status = status; this.code = code; }
    int status() { return status; }
    String code() { return code; }
}
