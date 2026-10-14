package io.saas.forge.tenantaccess.application.brand;

public interface BrandObjectStore {
    void put(String key, byte[] png);
    byte[] get(String key);
    void delete(String key);
}
