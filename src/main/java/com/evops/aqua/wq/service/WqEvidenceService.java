package com.evops.aqua.wq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.wq.entity.WqChangeEvidence;
import com.evops.aqua.wq.mapper.WqChangeEvidenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 水质模块证据链：所有关键动作只追加留痕，不提供修改/删除入口。
 */
@Service
public class WqEvidenceService {

    private final WqChangeEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;

    public WqEvidenceService(WqChangeEvidenceMapper evidenceMapper, ObjectMapper objectMapper) {
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
    }

    public void record(String bizType, Long bizId, String action, String detail,
                       Object before, Object after) {
        WqChangeEvidence evidence = new WqChangeEvidence();
        evidence.setBizType(bizType);
        evidence.setBizId(bizId);
        evidence.setAction(action);
        if (detail != null && detail.length() > 1000) {
            detail = detail.substring(0, 1000);
        }
        evidence.setDetail(detail);
        evidence.setBeforeJson(toJson(before));
        evidence.setAfterJson(toJson(after));
        Object principal = SecurityUtils.getSubject().getPrincipal();
        evidence.setOperator(principal == null ? "anonymous" : principal.toString());
        evidenceMapper.insert(evidence);
    }

    public List<WqChangeEvidence> list(String bizType, Long bizId) {
        QueryWrapper<WqChangeEvidence> wrapper = new QueryWrapper<>();
        wrapper.eq(bizType != null, "biz_type", bizType);
        wrapper.eq(bizId != null, "biz_id", bizId);
        wrapper.orderByAsc("id");
        return evidenceMapper.selectList(wrapper);
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return "[serialize-failed] " + obj;
        }
    }
}
