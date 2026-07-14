package com.talkwithneighbors.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that is intentionally available without a user session.
 *
 * <p>This annotation documents the access decision. Authentication remains
 * enforced by {@link RequireLogin}; changing the interceptor's default policy
 * is deliberately outside this marker's responsibility.</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
}
