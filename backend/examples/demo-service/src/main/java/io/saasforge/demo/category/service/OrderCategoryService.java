package io.saasforge.demo.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.demo.category.entity.OrderCategory;
import io.saasforge.demo.category.mapper.OrderCategoryMapper;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantInitializer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 演示租户初始化：新租户开通时预置默认订单分类。与普通业务代码一样不含租户条件，租户由 SDK 恢复。 */
@Service
public class OrderCategoryService implements TenantInitializer {
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("standard", "普通订单");
        DEFAULTS.put("urgent", "加急订单");
    }

    private final OrderCategoryMapper categoryMapper;

    public OrderCategoryService(OrderCategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    /** 只补齐缺少的预置分类：重试时之前已建立的分类保持不变。 */
    @Override
    public void initialize(TenantCreated tenant) {
        DEFAULTS.forEach((code, name) -> {
            boolean exists =
                    categoryMapper.exists(new LambdaQueryWrapper<OrderCategory>().eq(OrderCategory::getCode, code));
            if (!exists) {
                var category = new OrderCategory();
                category.setCode(code);
                category.setName(name);
                categoryMapper.insert(category);
            }
        });
    }

    public List<OrderCategory> list() {
        return categoryMapper.selectList(new LambdaQueryWrapper<OrderCategory>().orderByAsc(OrderCategory::getCode));
    }
}
