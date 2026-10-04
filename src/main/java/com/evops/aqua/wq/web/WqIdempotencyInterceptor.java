package com.evops.aqua.wq.web;

import com.evops.aqua.wq.entity.WqIdempotentRequest;
import com.evops.aqua.wq.mapper.WqIdempotentRequestMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 幂等拦截器：写请求携带 Idempotency-Key 时，首次执行并固化响应；
 * 同 key 的重复请求直接回放首次响应（响应头 Idempotency-Replay: true），不再执行业务，
 * 保证"水质采样计划与缺测补录"重复提交不会产生第二条结果。
 */
@Component
public class WqIdempotencyInterceptor implements HandlerInterceptor {

    public static final String ATTR_IDEMPOTENCY_KEY = "wq.idempotency.key";
    public static final String HEADER_KEY = "Idempotency-Key";
    private static final String HEADER_REPLAY = "Idempotency-Replay";

    private final WqIdempotentRequestMapper idempotentMapper;

    public WqIdempotencyInterceptor(WqIdempotentRequestMapper idempotentMapper) {
        this.idempotentMapper = idempotentMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (isSafeMethod(request.getMethod())) {
            return true;
        }
        String key = request.getHeader(HEADER_KEY);
        if (key == null || key.trim().isEmpty()) {
            return true;
        }
        key = key.trim();

        WqIdempotentRequest existing = idempotentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WqIdempotentRequest>()
                        .eq("request_key", key));
        if (existing != null) {
            if (existing.getResponseBody() == null) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"相同幂等键的请求正在处理中，请勿重复提交\"}");
                return false;
            }
            replay(response, existing);
            return false;
        }

        WqIdempotentRequest reservation = new WqIdempotentRequest();
        reservation.setRequestKey(key);
        reservation.setMethod(request.getMethod());
        reservation.setPath(request.getRequestURI());
        reservation.setStatusCode(HttpServletResponse.SC_OK);
        try {
            idempotentMapper.insert(reservation);
        } catch (DuplicateKeyException race) {
            existing = idempotentMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WqIdempotentRequest>()
                            .eq("request_key", key));
            if (existing != null && existing.getResponseBody() != null) {
                replay(response, existing);
                return false;
            }
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"相同幂等键的请求正在处理中，请勿重复提交\"}");
            return false;
        }
        request.setAttribute(ATTR_IDEMPOTENCY_KEY, reservation.getId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object idObj = request.getAttribute(ATTR_IDEMPOTENCY_KEY);
        if (!(idObj instanceof Long)) {
            return;
        }
        Long id = (Long) idObj;
        String body = null;
        if (response instanceof ContentCachingResponseWrapper) {
            ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) response;
            body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        }
        WqIdempotentRequest record = idempotentMapper.selectById(id);
        if (record != null) {
            record.setStatusCode(response.getStatus());
            record.setResponseBody(body);
            idempotentMapper.updateById(record);
        }
    }

    private void replay(HttpServletResponse response, WqIdempotentRequest recorded) throws java.io.IOException {
        response.setStatus(recorded.getStatusCode() == null ? HttpServletResponse.SC_OK : recorded.getStatusCode());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(HEADER_REPLAY, "true");
        if (recorded.getResponseBody() != null) {
            response.getWriter().write(recorded.getResponseBody());
        }
    }

    private boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }
}
