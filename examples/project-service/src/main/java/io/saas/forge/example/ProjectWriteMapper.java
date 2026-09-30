package io.saas.forge.example;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ProjectWriteMapper {
    boolean lock(long id);
    int expire(ProjectWriteRepository.WriteKey key);
    int claim(ProjectWriteRepository.WriteKey key);
    StoredResult find(ProjectWriteRepository.WriteKey key);
    int complete(@Param("key") ProjectWriteRepository.WriteKey key, @Param("result") ProjectWriteResult result);

    record StoredResult(String fingerprint, Integer status, String body, String location) {}
}
