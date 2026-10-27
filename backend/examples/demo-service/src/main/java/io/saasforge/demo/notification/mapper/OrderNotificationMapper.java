package io.saasforge.demo.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.demo.notification.entity.OrderNotification;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderNotificationMapper extends BaseMapper<OrderNotification> {}
