package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.InsuranceValuation;
import org.apache.ibatis.annotations.Select;

public interface InsuranceValuationMapper extends BaseMapper<InsuranceValuation> {

    /** 估值单号取号（H2 序列 IV-000001），插入前确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_insurance_valuation_no")
    Long nextValuationNo();
}
