package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.InsuranceValuationReminder;
import org.apache.ibatis.annotations.Select;

public interface InsuranceValuationReminderMapper extends BaseMapper<InsuranceValuationReminder> {

    /** 提醒单号取号（H2 序列 IR-000001） */
    @Select("SELECT NEXT VALUE FOR seq_insurance_reminder_no")
    Long nextReminderNo();
}
