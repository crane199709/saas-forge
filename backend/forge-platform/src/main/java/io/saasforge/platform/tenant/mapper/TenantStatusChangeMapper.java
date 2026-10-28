package io.saasforge.platform.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.platform.tenant.entity.TenantStatusChange;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantStatusChangeMapper extends BaseMapper<TenantStatusChange> {}
