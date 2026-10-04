package com.evops.aqua.wq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 幂等请求记录：携带 Idempotency-Key 的写接口，首次执行后固化响应，重复请求直接回放，
 * 保证"水质采样计划与缺测补录"重复提交不会产生第二条结果。
 */
@TableName("t_wq_idempotent_request")
public class WqIdempotentRequest {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestKey;
    private String method;
    private String path;
    private Integer statusCode;
    private String responseBody;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String requestKey) { this.requestKey = requestKey; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
