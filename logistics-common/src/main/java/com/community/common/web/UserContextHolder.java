package com.community.common.web;

import com.community.common.constant.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从当前请求上下文读取网关注入的用户信息。
 * 面试点：网关校验 JWT 后把 userId/userName 放进 Header 向下透传，
 * 业务服务通过 UserContextHolder 直接取，避免每个服务再解析一次 Token。
 */
public class UserContextHolder {

    public static Long getUserId() {
        String v = header(CommonConstants.HEADER_USER_ID);
        return v == null ? null : Long.valueOf(v);
    }

    public static String getUserName() {
        return header(CommonConstants.HEADER_USER_NAME);
    }

    private static String header(String name) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest req = attrs.getRequest();
        return req.getHeader(name);
    }
}
