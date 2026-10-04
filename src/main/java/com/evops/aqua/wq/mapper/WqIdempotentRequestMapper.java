package com.evops.aqua.wq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.aqua.wq.entity.WqIdempotentRequest;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WqIdempotentRequestMapper extends BaseMapper<WqIdempotentRequest> {
}
