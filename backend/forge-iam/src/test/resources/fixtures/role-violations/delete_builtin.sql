UPDATE role SET deleted = true
WHERE tenant_id = '019a0110-0000-7000-8000-000000000001' AND code = 'tenant-admin' AND NOT deleted;
